package com.roydon.dear.knowledge.process.chain;

import com.roydon.dear.knowledge.domain.entity.KnowledgeFileDO;
import lombok.Getter;
import lombok.Setter;
import org.springframework.ai.document.Document;

import java.util.List;

/**
 * 文件处理链路上下文 —— 在责任链各节点间传递处理状态。
 */
@Getter
@Setter
public class FileProcessContext {

    private KnowledgeFileDO fileDO;
    private List<Document> documents;

    public FileProcessContext(KnowledgeFileDO fileDO) {
        this.fileDO = fileDO;
    }
}
