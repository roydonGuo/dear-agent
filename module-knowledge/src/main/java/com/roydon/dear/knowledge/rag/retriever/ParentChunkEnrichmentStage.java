package com.roydon.dear.knowledge.rag.retriever;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.roydon.dear.knowledge.constant.MetadataKeyConstant;
import com.roydon.dear.knowledge.domain.entity.KnowledgeFileSegmentDO;
import com.roydon.dear.knowledge.service.IKnowledgeFileSegmentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 父分段补全阶段：子 chunk 检索结果 → 根据 parentChunkId 回查父分段完整文本
 * <p>
 * 子 chunk 用于向量/关键词检索（粒度细，匹配准），父分段包含完整语义上下文（粒度粗，信息全）。
 * 本阶段将子 chunk 的文本替换为父分段全文，确保 LLM 获得充足上下文。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ParentChunkEnrichmentStage implements RagStage {

    private final IKnowledgeFileSegmentService segmentService;

    @Override
    public void execute(RagContext ctx) {
        List<Document> mergedResults = ctx.getMergedResults();
        if (CollectionUtils.isEmpty(mergedResults)) return;

        // 收集所有 parentChunkId
        Set<String> parentChunkIds = new LinkedHashSet<>();
        for (Document doc : mergedResults) {
            Object parentId = doc.getMetadata().get(MetadataKeyConstant.PARENT_CHUNK_ID);
            if (parentId != null) {
                parentChunkIds.add(String.valueOf(parentId));
            }
        }

        if (parentChunkIds.isEmpty()) {
            log.debug("无父子分段关系，跳过父分段补全");
            return;
        }

        // 批量查询父分段
        List<KnowledgeFileSegmentDO> parentSegments = segmentService.list(
                new LambdaQueryWrapper<KnowledgeFileSegmentDO>()
                        .in(KnowledgeFileSegmentDO::getChunkId, parentChunkIds)
                        .select(KnowledgeFileSegmentDO::getChunkId, KnowledgeFileSegmentDO::getText));

        Map<String, String> parentTextMap = parentSegments.stream()
                .collect(Collectors.toMap(
                        KnowledgeFileSegmentDO::getChunkId,
                        KnowledgeFileSegmentDO::getText,
                        (a, b) -> a));

        ctx.setParentChunkTexts(parentTextMap);

        // 为每个子 chunk 标记父分段文本，供 formatAsContext 使用
        for (Document doc : mergedResults) {
            Object parentId = doc.getMetadata().get(MetadataKeyConstant.PARENT_CHUNK_ID);
            if (parentId != null) {
                String parentText = parentTextMap.get(String.valueOf(parentId));
                if (parentText != null) {
                    doc.getMetadata().put(MetadataKeyConstant.PARENT_CHUNK_TEXT, parentText);
                }
            }
        }

        int enrichedCount = (int) mergedResults.stream()
                .filter(d -> d.getMetadata().containsKey(MetadataKeyConstant.PARENT_CHUNK_TEXT)).count();
        log.debug("父分段补全完成: total={}, enriched={}", mergedResults.size(), enrichedCount);
    }
}
