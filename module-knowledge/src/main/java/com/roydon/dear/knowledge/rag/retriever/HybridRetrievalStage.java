package com.roydon.dear.knowledge.rag.retriever;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 混合检索阶段：并行执行语义检索和关键词检索
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HybridRetrievalStage implements RagStage {

    private final VectorStore vectorStore;
    private final ContentRetriever contentRetriever;

    private static final ExecutorService executor = Executors.newFixedThreadPool(2);

    @Override
    public void execute(RagContext ctx) {
        SearchRequest.Builder searchBuilder = SearchRequest.builder()
                .query(ctx.getQuery())
                .topK(ctx.getTopK())
                .similarityThreshold(0.3);

        if (StringUtils.hasText(ctx.getFilterExpression())) {
            searchBuilder.filterExpression(ctx.getFilterExpression());
        }

        SearchRequest searchRequest = searchBuilder.build();

        CompletableFuture<List<Document>> semanticFuture = CompletableFuture.supplyAsync(() -> {
            try {
                return vectorStore.similaritySearch(searchRequest);
            } catch (Exception e) {
                log.error("语义检索失败", e);
                return Collections.<Document>emptyList();
            }
        }, executor);

        CompletableFuture<List<Document>> keywordFuture = CompletableFuture.supplyAsync(() -> {
            try {
                return contentRetriever.keywordSearch(ctx.getQuery(), ctx.getResolvedFileIds(), ctx.getTopK());
            } catch (Exception e) {
                log.error("关键词检索失败", e);
                return Collections.<Document>emptyList();
            }
        }, executor);

        try {
            ctx.setSemanticResults(semanticFuture.get(30, TimeUnit.SECONDS));
            ctx.setKeywordResults(keywordFuture.get(30, TimeUnit.SECONDS));
        } catch (Exception e) {
            log.error("混合检索超时或失败", e);
        }

        log.debug("混合检索完成: semantic={}, keyword={}",
                ctx.getSemanticResults().size(), ctx.getKeywordResults().size());
    }
}
