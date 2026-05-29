package com.roydon.dear.knowledge.rag.retriever;

import lombok.Data;
import org.springframework.ai.document.Document;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
public class RagContext {

    private String query;
    private List<Long> knowledgeBaseIds;
    private int topK = 5;
    private String filterExpression;
    private List<Long> resolvedFileIds = new ArrayList<>();
    private Map<String, String> parentChunkTexts = new LinkedHashMap<>();
    private List<Document> semanticResults = new ArrayList<>();
    private List<Document> keywordResults = new ArrayList<>();
    private List<Document> mergedResults = new ArrayList<>();

    public static RagContext of(String query, List<Long> knowledgeBaseIds, int topK) {
        RagContext ctx = new RagContext();
        ctx.setQuery(query);
        ctx.setKnowledgeBaseIds(knowledgeBaseIds != null ? knowledgeBaseIds : new ArrayList<>());
        ctx.setTopK(topK);
        return ctx;
    }
}
