package com.roydon.dear.knowledge.service.impl;

import com.alicp.jetcache.anno.CacheInvalidate;
import com.alicp.jetcache.anno.CacheRefresh;
import com.alicp.jetcache.anno.CacheType;
import com.alicp.jetcache.anno.Cached;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.roydon.dear.common.exception.BusinessException;
import com.roydon.dear.core.service.FileStorage;
import com.roydon.dear.knowledge.domain.entity.KnowledgeFileDO;
import com.roydon.dear.knowledge.domain.entity.convertor.KnowledgeFileConvertor;
import com.roydon.dear.knowledge.domain.resp.KnowledgeFileResp;
import com.roydon.dear.knowledge.domain.resp.KnowledgeFileTreeNode;
import com.roydon.dear.knowledge.enums.FileMineType;
import com.roydon.dear.knowledge.enums.KnowledgeFileStatus;
import com.roydon.dear.knowledge.mapper.KnowledgeFileMapper;
import com.roydon.dear.knowledge.process.FileProcessStrategy;
import com.roydon.dear.knowledge.process.FileProcessStrategyFactory;
import com.roydon.dear.knowledge.service.IKnowledgeFileService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeFileServiceImpl extends ServiceImpl<KnowledgeFileMapper, KnowledgeFileDO> implements IKnowledgeFileService {

    private static final String CACHE_NAME = ":knowledge_file:";
    private static final String CACHE_NAME_ID = "id:";
    private static final String CACHE_NAME_TREE = "tree:";
    private static final String MINIO_KEY_PREFIX = "knowledge";

    private final KnowledgeFileConvertor knowledgeFileConvertor;
    private final FileStorage fileStorage;

    @Override
    @Cached(name = CACHE_NAME + CACHE_NAME_TREE, key = "#baseId", cacheType = CacheType.BOTH, cacheNullValue = true, expire = 60, localExpire = 10, timeUnit = TimeUnit.MINUTES)
    @CacheRefresh(refresh = 50, timeUnit = TimeUnit.MINUTES)
    public List<KnowledgeFileTreeNode> buildTree(Long baseId) {
        List<KnowledgeFileDO> allFiles = lambdaQuery()
                .eq(KnowledgeFileDO::getBaseId, baseId)
                .orderByDesc(KnowledgeFileDO::getCreateTime)
                .list();

        Map<Long, List<KnowledgeFileDO>> parentIdMap = allFiles.stream()
                .collect(Collectors.groupingBy(KnowledgeFileDO::getParentId));

        return buildChildren(0L, parentIdMap);
    }

    @Override
    @Cached(name = CACHE_NAME + CACHE_NAME_ID, key = "#id", cacheType = CacheType.BOTH, cacheNullValue = true, expire = 60, localExpire = 10, timeUnit = TimeUnit.MINUTES)
    @CacheRefresh(refresh = 50, timeUnit = TimeUnit.MINUTES)
    public KnowledgeFileResp findById(Long id) {
        KnowledgeFileDO entity = getById(id);
        if (entity == null) {
            return null;
        }
        return toRespWithUrl(entity);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public KnowledgeFileResp uploadFile(MultipartFile file, Long baseId, Long parentId) {
        String originalFilename = file.getOriginalFilename();
        String extension = getExtension(originalFilename);
        String name = originalFilename != null
                ? originalFilename.replaceAll("\\.[^.]+$", "")
                : "untitled";

        String mineType = detectMineType(extension, file.getContentType());
        String storagePath = MINIO_KEY_PREFIX + "/" + baseId + "/" + UUID.randomUUID().toString().replace("-", "") + "." + extension;

        byte[] fileBytes = null;
        try {
            fileBytes = file.getBytes();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        try {
            fileStorage.upload(fileStorage.getDefaultBucket(), storagePath, new java.io.ByteArrayInputStream(fileBytes), file.getSize(), mineType, false);
        } catch (Exception e) {
            log.error("MinIO upload failed for knowledge file, name={}", originalFilename, e);
            throw new BusinessException("文件上传失败: " + e.getMessage());
        }

        // 如果是 markdown 或文本类型，将内容解析出来存到 content 字段
        String content = null;
        if (isTextBasedFile(mineType, extension)) {
            content = new String(fileBytes, java.nio.charset.StandardCharsets.UTF_8);
            log.info("Text content extracted for file: name={}, contentLength={}", originalFilename, content.length());
        }

        KnowledgeFileDO entity = new KnowledgeFileDO();
        entity.setBaseId(baseId);
        entity.setParentId(parentId != null ? parentId : 0L);
        // name 长度限制50，且带上后缀
        int limitSize = 49 - extension.length();
        name = name.length() > limitSize ? name.substring(0, limitSize) : name;
        entity.setName(name + "." + extension);
        entity.setFileType("file");
        entity.setMineType(mineType);
        entity.setContent(content);
        entity.setStoragePath(storagePath);
        entity.setFileSize(file.getSize());
        entity.setCreateTime(LocalDateTime.now());
        entity.setUpdateTime(LocalDateTime.now());
        entity.setStatus(KnowledgeFileStatus.INIT);

        resolveAncestors(entity);
        save(entity);

        log.info("Knowledge file uploaded: id={}, name={}, size={}, storagePath={}",
                entity.getId(), originalFilename, file.getSize(), storagePath);

        // todo 文档分段/向量化
//        FileProcessStrategy fileProcessStrategy = fileProcessStrategyFactory.get(FileMineType.valueOf(mineType));
//        if (fileProcessStrategy != null) {
//            fileProcessStrategy.processFile(entity, new java.io.ByteArrayInputStream(fileBytes));
//        }
//
//        log.info("Knowledge file processed: id={}, name={}",
//                entity.getId(), originalFilename);
//        // 更新文档状态
//        entity.setStatus(KnowledgeFileStatus.CONVERTED);
//        entity.setConvertedDocUrl(fileUrl);
//        result = knowledgeDocumentService.updateById(document);

        return toRespWithUrl(entity);
    }

    @Override
    public String getFileUrl(Long id) {
        KnowledgeFileDO entity = getById(id);
        if (entity == null) {
            throw new BusinessException("文件不存在");
        }
        if (entity.getStoragePath() == null || entity.getStoragePath().isEmpty()) {
            return null;
        }
        return fileStorage.getFileUrl(entity.getStoragePath());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteCascade(Long id) {
        Set<Long> idsToDelete = new HashSet<>();
        collectChildrenIds(id, idsToDelete);
        idsToDelete.add(id);

        // 删除 MinIO 中的文件
        for (Long fileId : idsToDelete) {
            KnowledgeFileDO entity = getById(fileId);
            if (entity != null && entity.getStoragePath() != null && !entity.getStoragePath().isEmpty()) {
                try {
                    fileStorage.delete(fileStorage.getDefaultBucket(), entity.getStoragePath());
                    log.info("MinIO file deleted: id={}, storagePath={}", fileId, entity.getStoragePath());
                } catch (Exception e) {
                    log.warn("Failed to delete MinIO file: id={}, storagePath={}", fileId, entity.getStoragePath(), e);
                }
            }
        }

        removeByIds(idsToDelete);
    }

    @Override
    public boolean save(KnowledgeFileDO entity) {
        resolveAncestors(entity);
        if (entity.getCreateTime() == null) {
            entity.setCreateTime(LocalDateTime.now());
        }
        if (entity.getUpdateTime() == null) {
            entity.setUpdateTime(LocalDateTime.now());
        }
        return super.save(entity);
    }

    @Override
    public boolean updateById(KnowledgeFileDO entity) {
        resolveAncestors(entity);
        entity.setUpdateTime(LocalDateTime.now());
        return super.updateById(entity);
    }

    // ---- cache eviction ----

    @Override
    @CacheInvalidate(name = CACHE_NAME + CACHE_NAME_TREE, key = "#baseId")
    public void evictTree(Long baseId) {
    }

    @Override
    @CacheInvalidate(name = CACHE_NAME + CACHE_NAME_ID, key = "#id")
    public void evictById(Long id) {
    }

    // ---- private helpers ----

    private List<KnowledgeFileTreeNode> buildChildren(Long parentId, Map<Long, List<KnowledgeFileDO>> parentIdMap) {
        List<KnowledgeFileDO> children = parentIdMap.getOrDefault(parentId, Collections.emptyList());
        List<KnowledgeFileTreeNode> result = new ArrayList<>();

        for (KnowledgeFileDO file : children) {
            KnowledgeFileTreeNode node = KnowledgeFileTreeNode.builder()
                    .id(file.getId())
                    .name(file.getName())
                    .type(file.getFileType())
                    .fileType(file.getMineType())
                    .content(file.getContent())
                    .storagePath(file.getStoragePath())
                    .fileSize(file.getFileSize())
                    .fileUrl(resolveFileUrl(file.getStoragePath()))
                    .createTime(file.getCreateTime())
                    .updateTime(file.getUpdateTime())
                    .children(new ArrayList<>())
                    .build();

            if ("folder".equals(file.getFileType())) {
                node.setChildren(buildChildren(file.getId(), parentIdMap));
            }

            result.add(node);
        }

        return result;
    }

    private KnowledgeFileResp toRespWithUrl(KnowledgeFileDO entity) {
        KnowledgeFileResp resp = knowledgeFileConvertor.toResp(entity);
        resp.setFileUrl(resolveFileUrl(entity.getStoragePath()));
        return resp;
    }

    private String resolveFileUrl(String storagePath) {
        if (storagePath == null || storagePath.isEmpty()) {
            return null;
        }
        return fileStorage.getFileUrl(storagePath);
    }

    private void resolveAncestors(KnowledgeFileDO entity) {
        Long parentId = entity.getParentId();
        if (parentId == null || parentId == 0) {
            entity.setAncestors("0");
            entity.setParentId(0L);
        } else {
            KnowledgeFileDO parent = getById(parentId);
            if (parent == null) {
                throw new BusinessException("父节点不存在: " + parentId);
            }
            entity.setAncestors(parent.getAncestors() + "," + parentId);
        }
    }

    private void collectChildrenIds(Long parentId, Set<Long> ids) {
        List<KnowledgeFileDO> children = lambdaQuery()
                .eq(KnowledgeFileDO::getParentId, parentId)
                .list();
        for (KnowledgeFileDO child : children) {
            ids.add(child.getId());
            if ("folder".equals(child.getFileType())) {
                collectChildrenIds(child.getId(), ids);
            }
        }
    }

    private String getExtension(String filename) {
        if (filename == null || !filename.contains(".")) {
            return "bin";
        }
        return filename.substring(filename.lastIndexOf(".") + 1).toLowerCase();
    }

    private String detectMineType(String extension, String contentType) {
        if (contentType != null && !contentType.isEmpty() && !"application/octet-stream".equals(contentType)) {
            return contentType;
        }
        return switch (extension) {
            case "md" -> "text/markdown";
            case "txt" -> "text/plain";
            case "pdf" -> "application/pdf";
            case "jpg", "jpeg" -> "image/jpeg";
            case "png" -> "image/png";
            case "gif" -> "image/gif";
            case "webp" -> "image/webp";
            case "svg" -> "image/svg+xml";
            case "mp4" -> "video/mp4";
            case "webm" -> "video/webm";
            case "mov" -> "video/quicktime";
            case "avi" -> "video/x-msvideo";
            case "mkv" -> "video/x-matroska";
            case "mp3" -> "audio/mpeg";
            case "wav" -> "audio/wav";
            case "doc" -> "application/msword";
            case "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "xls" -> "application/vnd.ms-excel";
            case "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case "ppt" -> "application/vnd.ms-powerpoint";
            case "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation";
            case "csv" -> "text/csv";
            case "json" -> "application/json";
            case "xml" -> "application/xml";
            case "html", "htm" -> "text/html";
            case "css" -> "text/css";
            case "js" -> "application/javascript";
            case "java" -> "text/x-java-source";
            case "py" -> "text/x-python";
            case "yaml", "yml" -> "text/yaml";
            default -> "application/octet-stream";
        };
    }

    private boolean isTextBasedFile(String mineType, String extension) {
        if (mineType != null && (mineType.startsWith("text/")
                || mineType.equals("application/json")
                || mineType.equals("application/xml")
                || mineType.equals("application/javascript"))) {
            return true;
        }
        return extension != null && (extension.equals("md") || extension.equals("txt")
                || extension.equals("json") || extension.equals("xml")
                || extension.equals("html") || extension.equals("htm")
                || extension.equals("css") || extension.equals("js")
                || extension.equals("java") || extension.equals("py")
                || extension.equals("yaml") || extension.equals("yml")
                || extension.equals("csv"));
    }
}
