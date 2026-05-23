package com.roydon.dear.knowledge.rag.reader;

import com.roydon.dear.knowledge.domain.entity.KnowledgeFileDO;
import com.roydon.dear.knowledge.enums.FileMineType;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.util.List;

@Component
public class DocumentReaderFactory {

    @Autowired
    private List<FileReaderStrategy> strategies;

    public List<Document> read(KnowledgeFileDO fileDO) throws IOException {
        for (FileReaderStrategy strategy : strategies) {
            if (strategy.supports(FileMineType.fromValue(fileDO.getMineType()))) {
                return strategy.read(fileDO);
            }
        }
        throw new IllegalArgumentException("不支持的文件类型: " + fileDO.getName());
    }
}
