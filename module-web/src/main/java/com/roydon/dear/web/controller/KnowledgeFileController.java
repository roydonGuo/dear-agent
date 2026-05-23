package com.roydon.dear.web.controller;

import com.roydon.dear.common.BaseResult;
import com.roydon.dear.common.exception.BusinessException;
import com.roydon.dear.knowledge.domain.entity.KnowledgeFileDO;
import com.roydon.dear.knowledge.domain.entity.convertor.KnowledgeFileConvertor;
import com.roydon.dear.knowledge.domain.req.KnowledgeFileRequest;
import com.roydon.dear.knowledge.domain.resp.KnowledgeFileResp;
import com.roydon.dear.knowledge.domain.resp.KnowledgeFileTreeNode;
import com.roydon.dear.knowledge.enums.FileMineType;
import com.roydon.dear.knowledge.enums.KnowledgeFileStatus;
import com.roydon.dear.knowledge.process.FileProcessComponent;
import com.roydon.dear.knowledge.process.FileProcessStrategy;
import com.roydon.dear.knowledge.process.FileProcessStrategyFactory;
import com.roydon.dear.knowledge.service.IKnowledgeFileService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

@Slf4j
@Validated
@Tag(name = "知识库文件管理", description = "知识库文件树查询、文件/文件夹CRUD、文件上传下载")
@RestController
@RequestMapping("/knowledge-file")
@RequiredArgsConstructor
public class KnowledgeFileController {

    private final IKnowledgeFileService knowledgeFileService;
    private final KnowledgeFileConvertor knowledgeFileConvertor;
    private final FileProcessStrategyFactory fileProcessStrategyFactory;
    private final FileProcessComponent fileProcessComponent;

    @GetMapping("/tree")
    @Operation(summary = "查询文件树", description = "根据知识库ID查询完整的文件树结构")
    public BaseResult<List<KnowledgeFileTreeNode>> tree(@RequestParam Long baseId) {
        log.info("查询文件树: baseId={}", baseId);
        List<KnowledgeFileTreeNode> tree = knowledgeFileService.buildTree(baseId);
        return BaseResult.newSuccess(tree);
    }

    @GetMapping("/{id}")
    @Operation(summary = "查询文件详情", description = "根据ID查询文件详情（含内容和访问URL）")
    public BaseResult<KnowledgeFileResp> getById(@PathVariable Long id) {
        log.info("查询文件详情: id={}", id);
        KnowledgeFileResp resp = knowledgeFileService.findById(id);
        if (resp == null) {
            throw new BusinessException("文件不存在");
        }
        return BaseResult.newSuccess(resp);
    }

    @PostMapping
    @Operation(summary = "创建文件/文件夹", description = "创建新的文件或文件夹节点（文本类型）")
    public BaseResult<KnowledgeFileResp> create(@Valid @RequestBody KnowledgeFileRequest request) {
        log.info("创建文件: request={}", request);
        KnowledgeFileDO entity = knowledgeFileConvertor.toEntity(request);
        entity.setStatus(KnowledgeFileStatus.INIT);
        knowledgeFileService.save(entity);
        knowledgeFileService.evictTree(entity.getBaseId());
        return BaseResult.newSuccess(knowledgeFileConvertor.toResp(entity));
    }

    @PostMapping("/upload")
    @Operation(summary = "上传文件", description = "上传文件到知识库，支持 PDF/图片/Word/文本等格式，自动存储到 MinIO")
    public BaseResult<KnowledgeFileResp> upload(
            @RequestParam MultipartFile file,
            @RequestParam Long baseId,
            @RequestParam(required = false) Long parentId) {
        log.info("上传文件: name={}, size={}, baseId={}, parentId={}",
                file.getOriginalFilename(), file.getSize(), baseId, parentId);
        KnowledgeFileResp resp = knowledgeFileService.uploadFile(file, baseId, parentId);
        knowledgeFileService.evictTree(baseId);
        return BaseResult.newSuccess(resp);
    }

    @GetMapping("/{id}/url")
    @Operation(summary = "获取文件访问URL", description = "获取文件的 MinIO 预签名访问 URL")
    public BaseResult<String> getFileUrl(@PathVariable Long id) {
        String url = knowledgeFileService.getFileUrl(id);
        if (url == null) {
            throw new BusinessException("该文件为纯文本文件，无存储路径");
        }
        return BaseResult.newSuccess(url);
    }

    @GetMapping("/{id}/download")
    @Operation(summary = "下载文件", description = "以附件形式下载文件，浏览器触发另存为")
    public ResponseEntity<BaseResult<String>> download(@PathVariable Long id) {
        KnowledgeFileDO entity = knowledgeFileService.getById(id);
        if (entity == null) {
            throw new BusinessException("文件不存在");
        }
        String url = knowledgeFileService.getFileUrl(id);
        if (url == null) {
            throw new BusinessException("该文件无存储路径，无法下载");
        }
        // 返回预签名 URL 供前端重定向下载，或返回 302 重定向
        String filename = entity.getName();
        String extension = "";
        if (entity.getStoragePath() != null && entity.getStoragePath().contains(".")) {
            extension = entity.getStoragePath().substring(entity.getStoragePath().lastIndexOf("."));
        }
        String encodedFilename = URLEncoder.encode(filename + extension, StandardCharsets.UTF_8)
                .replace("+", "%20");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename*=UTF-8''" + encodedFilename)
                .body(BaseResult.newSuccess(url));
    }

    @PutMapping("/{id}")
    @Operation(summary = "更新文件/文件夹", description = "更新文件或文件夹的名称、内容等信息")
    public BaseResult<KnowledgeFileResp> update(@PathVariable Long id, @Valid @RequestBody KnowledgeFileRequest request) {
        log.info("更新文件: id={}, request={}", id, request);
        KnowledgeFileDO entity = knowledgeFileConvertor.toEntity(request);
        entity.setId(id);
        knowledgeFileService.updateById(entity);
        knowledgeFileService.evictById(id);
        knowledgeFileService.evictTree(request.getBaseId());
        KnowledgeFileResp resp = knowledgeFileService.findById(id);
        return BaseResult.newSuccess(resp);
    }

    @DeleteMapping("/{id}")
    @Transactional(rollbackFor = Exception.class)
    @Operation(summary = "删除文件/文件夹", description = "删除指定节点，若为文件夹则级联删除所有子节点并清理 MinIO 文件")
    public BaseResult<String> delete(@PathVariable Long id) {
        log.info("删除文件: id={}", id);
        KnowledgeFileDO entity = knowledgeFileService.getById(id);
        if (entity == null) {
            throw new BusinessException("文件不存在");
        }
        knowledgeFileService.deleteCascade(id);
        knowledgeFileService.evictById(id);
        knowledgeFileService.evictTree(entity.getBaseId());
        return BaseResult.newSuccess("删除成功");
    }

    @PostMapping("/embed/{fileId}")
    public BaseResult<String> processFile(@PathVariable Long fileId) {
        KnowledgeFileDO entity = knowledgeFileService.getById(fileId);
        if (entity == null) {
            throw new BusinessException("文件不存在");
        }
        if (entity.getFileType().equals("folder")) {
            throw new BusinessException("不可操作文件夹，请选择文件！");
        }
        fileProcessComponent.processFile(entity);
        return BaseResult.newSuccess();
    }
}
