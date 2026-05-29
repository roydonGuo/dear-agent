package com.roydon.dear.knowledge.rag.retriever;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * RAG 管道编排器：按顺序执行各阶段
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RagPipeline {

    private final QueryAnalysisStage queryAnalysisStage;
    private final HybridRetrievalStage hybridRetrievalStage;
    private final RrfRerankingStage rrfRerankingStage;
    private final ParentChunkEnrichmentStage parentChunkEnrichmentStage;

    public List<Document> execute(RagContext ctx) {
        log.debug("开始执行 RAG 管道: query={}, knowledgeBaseIds={}", ctx.getQuery(), ctx.getKnowledgeBaseIds());

        queryAnalysisStage.execute(ctx);
        hybridRetrievalStage.execute(ctx);
        rrfRerankingStage.execute(ctx);
        parentChunkEnrichmentStage.execute(ctx);

        log.debug("RAG 管道完成: 最终结果={}", ctx.getMergedResults().size());
        return ctx.getMergedResults();
    }
}
