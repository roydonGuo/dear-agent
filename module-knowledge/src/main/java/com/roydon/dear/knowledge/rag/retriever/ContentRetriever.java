package com.roydon.dear.knowledge.rag.retriever;

import org.springframework.ai.document.Document;

import java.util.List;

public interface ContentRetriever {

    /**
     * 关键词检索（BM25），支持过滤条件
     *
     * @param query         查询文本
     * @param fileIdFilters 按 fileId 过滤（null 或空列表表示不过滤）
     * @param topK          返回数量
     * @return 检索到的文档列表
     */
    List<Document> keywordSearch(String query, List<Long> fileIdFilters, int topK);
}
