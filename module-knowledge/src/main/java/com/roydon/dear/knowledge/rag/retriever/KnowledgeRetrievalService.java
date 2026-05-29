package com.roydon.dear.knowledge.rag.retriever;

import com.roydon.dear.knowledge.constant.MetadataKeyConstant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 知识库检索服务 —— Agent 入口
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeRetrievalService {

    private final RagPipeline ragPipeline;

    private static final int DEFAULT_TOP_K = 5;

    /**
     * 检索知识库
     *
     * @param query            用户查询
     * @param knowledgeBaseIds 知识库 ID 列表（为空则检索全部）
     * @param topK             返回条数
     * @return 检索并重排序后的文档列表
     */
    public List<Document> retrieve(String query, List<Long> knowledgeBaseIds, int topK) {
        if (query == null || query.isBlank()) {
            return Collections.emptyList();
        }
        int k = topK > 0 ? topK : DEFAULT_TOP_K;
        try {
            RagContext ctx = RagContext.of(query, knowledgeBaseIds, k);
            return ragPipeline.execute(ctx);
        } catch (Exception e) {
            log.error("知识库检索异常: query={}", query, e);
            return Collections.emptyList();
        }
    }

    /**
     * 将检索结果格式化为上下文字符串，便于注入 System Prompt。
     * 优先使用父分段完整文本（parentChunkText），回退到子 chunk 文本。
     */
    public String formatAsContext(List<Document> documents) {
        if (CollectionUtils.isEmpty(documents)) {
            return "";
        }
        // 去重：同一父分段只保留一份
        Set<String> seenParentChunkIds = new HashSet<>();
        List<Document> deduplicated = new ArrayList<>();
        for (Document doc : documents) {
            Object parentChunkId = doc.getMetadata().get(MetadataKeyConstant.PARENT_CHUNK_ID);
            Object parentText = doc.getMetadata().get(MetadataKeyConstant.PARENT_CHUNK_TEXT);
            if (parentChunkId != null && parentText != null) {
                String key = String.valueOf(parentChunkId);
                if (seenParentChunkIds.add(key)) {
                    deduplicated.add(doc);
                }
            } else {
                deduplicated.add(doc);
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("\n# 知识库检索结果：\n");
        int idx = 0;
        for (Document doc : deduplicated) {
            Object parentText = doc.getMetadata().get(MetadataKeyConstant.PARENT_CHUNK_TEXT);
            String text = parentText != null ? parentText.toString() : doc.getText();
            sb.append("## 片段 ").append(++idx).append("\n");
            sb.append(text).append("\n\n");
        }
        return sb.toString();
    }
}
