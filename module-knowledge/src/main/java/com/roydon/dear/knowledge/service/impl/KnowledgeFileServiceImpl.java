package com.roydon.dear.knowledge.service.impl;

import com.alicp.jetcache.anno.CacheInvalidate;
import com.alicp.jetcache.anno.CacheRefresh;
import com.alicp.jetcache.anno.CacheType;
import com.alicp.jetcache.anno.Cached;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.roydon.dear.common.exception.BusinessException;
import com.roydon.dear.knowledge.domain.entity.KnowledgeFileDO;
import com.roydon.dear.knowledge.domain.entity.convertor.KnowledgeFileConvertor;
import com.roydon.dear.knowledge.domain.resp.KnowledgeFileResp;
import com.roydon.dear.knowledge.domain.resp.KnowledgeFileTreeNode;
import com.roydon.dear.knowledge.mapper.KnowledgeFileMapper;
import com.roydon.dear.knowledge.service.IKnowledgeFileService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class KnowledgeFileServiceImpl extends ServiceImpl<KnowledgeFileMapper, KnowledgeFileDO> implements IKnowledgeFileService {

    public static final String CACHE_NAME = ":knowledge_file:";
    public static final String CACHE_NAME_ID = "id:";
    public static final String CACHE_NAME_TREE = "tree:";

    private final KnowledgeFileConvertor knowledgeFileConvertor;

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
        return knowledgeFileConvertor.toResp(entity);
    }

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

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteCascade(Long id) {
        Set<Long> idsToDelete = new HashSet<>();
        collectChildrenIds(id, idsToDelete);
        idsToDelete.add(id);
        removeByIds(idsToDelete);
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

    @Override
    @CacheInvalidate(name = CACHE_NAME + CACHE_NAME_TREE, key = "#baseId")
    public void evictTree(Long baseId) {
        // 仅触发树缓存失效
    }

    @Override
    @CacheInvalidate(name = CACHE_NAME + CACHE_NAME_ID, key = "#id")
    public void evictById(Long id) {
        // 仅触发单条缓存失效
    }
}
