package com.roydon.dear.knowledge.rag.retriever;

import com.roydon.dear.knowledge.constant.MetadataKeyConstant;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import java.util.*;
import java.util.stream.Collectors;

/**
 * RRF (Reciprocal Rank Fusion) 重排序阶段
 * <p>
 * 将语义检索和关键词检索的结果合并去重，按 RRF 分数排序。
 * RRF 公式: score(d) = Σ 1 / (k + rank_i(d))，其中 k 默认 60。
 */
@Slf4j
@Component
public class RrfRerankingStage implements RagStage {

    private static final double K = 60.0;

    @Override
    public void execute(RagContext ctx) {
        List<Document> semanticResults = ctx.getSemanticResults();
        List<Document> keywordResults = ctx.getKeywordResults();

        if (CollectionUtils.isEmpty(semanticResults) && CollectionUtils.isEmpty(keywordResults)) {
            ctx.setMergedResults(Collections.emptyList());
            return;
        }

        // id -> Document（merge source）
        Map<String, Document> docMap = new LinkedHashMap<>();
        // id -> RRF score
        Map<String, Double> rrfScores = new HashMap<>();

        // 累加语义检索的 RRF 分数
        accumulateRrf(semanticResults, docMap, rrfScores);
        // 累加关键词检索的 RRF 分数
        accumulateRrf(keywordResults, docMap, rrfScores);

        // 按 RRF 分数降序排序
        List<Document> merged = rrfScores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(ctx.getTopK())
                .map(entry -> {
                    Document doc = docMap.get(entry.getKey());
                    if (doc != null) {
                        return doc.mutate().score(entry.getValue()).build();
                    }
                    return null;
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        ctx.setMergedResults(merged);
        log.debug("RRF 重排序完成: merged={}", merged.size());
    }

    private void accumulateRrf(List<Document> results, Map<String, Document> docMap, Map<String, Double> rrfScores) {
        if (CollectionUtils.isEmpty(results)) return;

        for (int i = 0; i < results.size(); i++) {
            Document doc = results.get(i);
            String docId = resolveId(doc);

            docMap.putIfAbsent(docId, doc);
            double rrf = 1.0 / (K + i + 1);  // rank 从 1 开始
            rrfScores.merge(docId, rrf, Double::sum);
        }
    }

    private String resolveId(Document doc) {
        // 优先使用 document id，没有则用 text hash 去重
        if (doc.getId() != null && !doc.getId().isBlank()) {
            return doc.getId();
        }
        Object chunkId = doc.getMetadata().get(MetadataKeyConstant.CHUNK_ID);
        if (chunkId != null) {
            return chunkId.toString();
        }
        return String.valueOf(Objects.hash(doc.getText()));
    }
}
