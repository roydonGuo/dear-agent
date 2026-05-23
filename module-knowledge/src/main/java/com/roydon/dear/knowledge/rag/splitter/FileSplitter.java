package com.roydon.dear.knowledge.rag.splitter;

import org.springframework.ai.document.Document;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * spring ai的文档解析不太行
 * todo 后续优化使用langchain4j的文档解析
 */
public interface FileSplitter {

    List<Document> split(Document var1);

    default List<Document> splitAll(List<Document> documents) {
        return (List) documents.stream().flatMap((document) -> {
            return this.split(document).stream();
        }).collect(Collectors.toList());
    }

    default List<Document> splitAll(Document... documents) {
        return isNullOrEmpty(documents) ? Collections.emptyList() : this.splitAll(Arrays.asList(documents));
    }

    private boolean isNullOrEmpty(Object[] array) {
        return array == null || array.length == 0;
    }
}
