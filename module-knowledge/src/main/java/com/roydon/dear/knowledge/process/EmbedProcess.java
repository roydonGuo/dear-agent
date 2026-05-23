package com.roydon.dear.knowledge.process;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.TypeReference;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.roydon.dear.knowledge.domain.entity.KnowledgeFileDO;
import com.roydon.dear.knowledge.domain.entity.KnowledgeFileSegmentDO;
import com.roydon.dear.knowledge.enums.FileSegmentStatus;
import com.roydon.dear.knowledge.enums.KnowledgeFileStatus;
import com.roydon.dear.knowledge.service.IKnowledgeFileSegmentService;
import com.roydon.dear.knowledge.service.IKnowledgeFileService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 向量化处理 —— 将分段后的文本批量向量化并存入 Elasticsearch。
 *
 * @author roydon
 * @since 2026-05-23
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmbedProcess {

    private final IKnowledgeFileSegmentService knowledgeSegmentService;
    private final IKnowledgeFileService knowledgeFileService;
    private final VectorStore vectorStore;

    private static final int EMBEDDING_BATCH_SIZE = 100;

    public boolean embedAndStore(KnowledgeFileDO fileDO) {
        if (fileDO.getStatus() == KnowledgeFileStatus.VECTOR_STORED) {
            return true;
        }
        if (fileDO.getStatus() != KnowledgeFileStatus.CHUNKED) {
            log.warn("文件 {} 状态不是 CHUNKED，当前状态: {}", fileDO.getId(), fileDO.getStatus());
            return false;
        }

        LambdaQueryWrapper<KnowledgeFileSegmentDO> queryWrapper = Wrappers.<KnowledgeFileSegmentDO>lambdaQuery()
                .eq(KnowledgeFileSegmentDO::getFileId, fileDO.getId())
                .eq(KnowledgeFileSegmentDO::getStatus, FileSegmentStatus.CHUNKED)
                .eq(KnowledgeFileSegmentDO::getSkipEmbedding, 0);

        int pageNum = 1;
        Page<KnowledgeFileSegmentDO> page = knowledgeSegmentService.page(
                new Page<>(pageNum, EMBEDDING_BATCH_SIZE), queryWrapper);

        while (!page.getRecords().isEmpty()) {
            List<KnowledgeFileSegmentDO> segmentList = page.getRecords();

            List<Document> documents = segmentList.stream()
                    .map(segment -> {
                        Map<String, Object> metadata = segment.getMetadata() != null
                                ? JSON.parseObject(segment.getMetadata(),
                                        new TypeReference<Map<String, Object>>() {})
                                : new HashMap<>();
                        return new Document(segment.getText(), metadata);
                    })
                    .toList();

            try {
                vectorStore.add(documents);
                log.info("向量化并存储 {} 个片段", documents.size());
            } catch (Exception e) {
                log.error("向量存储失败: {}", e.getMessage(), e);
                return false;
            }

            for (KnowledgeFileSegmentDO segment : segmentList) {
                segment.setStatus(FileSegmentStatus.VECTOR_STORED);
                knowledgeSegmentService.updateById(segment);
            }

            if (!page.hasNext()) {
                break;
            }
            pageNum++;
            page = knowledgeSegmentService.page(
                    new Page<>(pageNum, EMBEDDING_BATCH_SIZE), queryWrapper);
        }

        long remainingCount = knowledgeSegmentService.count(Wrappers.<KnowledgeFileSegmentDO>lambdaQuery()
                .eq(KnowledgeFileSegmentDO::getFileId, fileDO.getId())
                .eq(KnowledgeFileSegmentDO::getStatus, FileSegmentStatus.CHUNKED)
                .eq(KnowledgeFileSegmentDO::getSkipEmbedding, 0));

        if (remainingCount == 0) {
            fileDO.setStatus(KnowledgeFileStatus.VECTOR_STORED);
            knowledgeFileService.updateById(fileDO);
            log.info("文件 {} 向量化完成", fileDO.getId());
            return true;
        }

        log.warn("向量存储未完全完成，剩余 {} 个片段未处理", remainingCount);
        return false;
    }
}
