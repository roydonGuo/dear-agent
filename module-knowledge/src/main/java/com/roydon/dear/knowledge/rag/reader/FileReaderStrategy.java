package com.roydon.dear.knowledge.rag.reader;

import com.roydon.dear.knowledge.domain.entity.KnowledgeFileDO;
import com.roydon.dear.knowledge.enums.FileMineType;
import org.springframework.ai.document.Document;

import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * 文件读取策略接口
 */
public interface FileReaderStrategy {

    /**
     * 判断是否支持该文件
     */
    boolean supports(FileMineType mineType);

    /**
     * 读取文件并返回 Document 列表
     */
    List<Document> read(KnowledgeFileDO fileDO) throws IOException;
}
