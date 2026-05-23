package com.roydon.dear.knowledge.process;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.roydon.dear.common.lock.DistributeLock;
import com.roydon.dear.knowledge.domain.entity.KnowledgeFileDO;
import com.roydon.dear.knowledge.domain.entity.KnowledgeFileSegmentDO;
import com.roydon.dear.knowledge.enums.FileSegmentStatus;
import com.roydon.dear.knowledge.enums.KnowledgeFileStatus;
import com.roydon.dear.knowledge.service.IKnowledgeFileSegmentService;
import com.roydon.dear.knowledge.service.IKnowledgeFileService;
import com.roydon.dear.model.registry.ModelRegistry;
import com.roydon.dear.session.enums.ModelCategoryEnum;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.xwpf.usermodel.TextSegment;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * EmbedProcess
 *
 * @AUTHOR: roydon
 * @DATE: 2026/5/23
 **/
@Slf4j
@Service
@RequiredArgsConstructor
public class EmbedProcess {
    private final IKnowledgeFileSegmentService knowledgeSegmentService;
    private final ModelRegistry modelRegistry;
    private final IKnowledgeFileService knowledgeFileService;
    private static final int EMBEDDING_BATCH_SIZE = 9;

    public boolean embedAndStore(KnowledgeFileDO fileDO) {
        if (fileDO.getStatus() == KnowledgeFileStatus.VECTOR_STORED) {
            return true;
        }

        if (fileDO.getStatus() != KnowledgeFileStatus.CHUNKED) {
            return false;
        }

        // 分页扫描全部fileId为文件id且status为CHUNKED的文档片段
        LambdaQueryWrapper<KnowledgeFileSegmentDO> queryWrapper = Wrappers.<KnowledgeFileSegmentDO>lambdaQuery()
                .eq(KnowledgeFileSegmentDO::getFileId, fileDO.getId())
                .eq(KnowledgeFileSegmentDO::getStatus, FileSegmentStatus.CHUNKED)
                .isNull(KnowledgeFileSegmentDO::getEmbeddingId)
                .eq(KnowledgeFileSegmentDO::getSkipEmbedding, 0);

        Page<KnowledgeFileSegmentDO> page = knowledgeSegmentService.page(new Page<>(1, 100), queryWrapper);

        while (page.getCurrent() == 1 || page.hasNext()) {
            List<KnowledgeFileSegmentDO> fileSegmentDOList = page.getRecords();
            List<Document> textSegments = fileSegmentDOList.stream().map(segment -> Document.from(segment.getText(), Metadata.from(segment.getMetadataMap()))).toList();
            // 获取嵌入向量
            EmbeddingModel embedModel = modelRegistry.get(ModelCategoryEnum.EMBEDDING.getCode());
            Response<List<Embedding>> embeddingResponse = embedModel.embed(textSegments);

            // 存储嵌入向量
            List<String> embeddingIds = elasticsearchEmbeddingStore.addAll(embeddingResponse.content(), textSegments);

            //todo 事务处理

            // 更新文档片段状态
            for (int i = 0; i < fileSegmentDOList.size(); i++) {
                String embeddingId = embeddingIds.get(i);
                KnowledgeFileSegmentDO knowledgeSegment = fileSegmentDOList.get(i);
                knowledgeSegment.setEmbeddingId(embeddingId);
                knowledgeSegment.setStatus(FileSegmentStatus.VECTOR_STORED);
                knowledgeSegmentService.updateById(knowledgeSegment);
            }

            // 继续扫描下一页
            page = knowledgeSegmentService.page(new Page<>(page.getCurrent() + 1, 100), queryWrapper);
        }

        //double check
        long segmentCount = knowledgeSegmentService.count(queryWrapper);
        if (segmentCount == 0) {
            // 更新文档状态
            fileDO.setStatus(KnowledgeFileStatus.VECTOR_STORED);
            return knowledgeFileService.updateById(fileDO);
        }

        log.warn("向量存储失败，存在部分分段没有存储成功，未成功的数量： " + segmentCount);
        return false;
    }

}
