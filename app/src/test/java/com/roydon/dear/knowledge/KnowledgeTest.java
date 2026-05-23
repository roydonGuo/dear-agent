package com.roydon.dear.knowledge;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

/**
 * 知识库向量检索测试
 *
 * @author roydon
 * @since 2026-05-24
 */
@Slf4j
@SpringBootTest
@ActiveProfiles("dev")
public class KnowledgeTest {

    @Resource
    private VectorStore vectorStore;

    /**
     * 基础向量检索 —— 输入查询文本，返回最相似的文档片段
     */
    @Test
    public void testSimilaritySearch() {
        String query = "什么是微服务架构";
        List<Document> results = vectorStore.similaritySearch(
                SearchRequest.query(query).withTopK(5).withSimilarityThreshold(0.5));

        log.info("查询: {}, 命中 {} 条结果", query, results.size());
        for (int i = 0; i < results.size(); i++) {
            Document doc = results.get(i);
            log.info("[{}] score={}, content={}, metadata={}",
                    i + 1, doc.getScore(), truncate(doc.getText(), 200), doc.getMetadata());
        }
    }

    /**
     * 带过滤条件的向量检索 —— 限定某个文件或知识库范围内的检索
     */
    @Test
    public void testSimilaritySearchWithFilter() {
        String query = "Spring Boot 配置";
        List<Document> results = vectorStore.similaritySearch(
                SearchRequest.query(query)
                        .withTopK(5)
                        .withSimilarityThreshold(0.3)
                        .withFilterExpression("file_id == 1"));

        log.info("带过滤条件查询: {}, 命中 {} 条", query, results.size());
        for (int i = 0; i < results.size(); i++) {
            Document doc = results.get(i);
            log.info("[{}] score={}, content={}, metadata={}",
                    i + 1, doc.getScore(), truncate(doc.getText(), 200), doc.getMetadata());
        }
    }

    /**
     * 最简检索 —— 直接用字符串查询
     */
    @Test
    public void testSimpleSearch() {
        String query = "知识库";
        List<Document> results = vectorStore.similaritySearch(query);

        log.info("简单查询: {}, 命中 {} 条 (默认TopK=4)", query, results.size());
        for (int i = 0; i < results.size(); i++) {
            Document doc = results.get(i);
            log.info("[{}] score={}, content={}",
                    i + 1, doc.getScore(), truncate(doc.getText(), 150));
        }
    }

    private static String truncate(String text, int maxLen) {
        if (text == null) return "null";
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }
}
