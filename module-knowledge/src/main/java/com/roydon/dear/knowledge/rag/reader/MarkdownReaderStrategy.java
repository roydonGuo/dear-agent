package com.roydon.dear.knowledge.rag.reader;

import com.roydon.dear.common.exception.BusinessException;
import com.roydon.dear.core.service.FileStorage;
import com.roydon.dear.knowledge.domain.entity.KnowledgeFileDO;
import com.roydon.dear.knowledge.enums.FileMineType;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.markdown.MarkdownDocumentReader;
import org.springframework.ai.reader.markdown.config.MarkdownDocumentReaderConfig;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;

@Service
@RequiredArgsConstructor
public class MarkdownReaderStrategy implements FileReaderStrategy {
    private final FileStorage fileStorage;

    @Override
    public boolean supports(FileMineType mineType) {
        return FileMineType.TEXT_MARKDOWN.equals(mineType);
    }

    @Override
    public List<Document> read(KnowledgeFileDO fileDO) throws IOException {
        String processedStoragePath = fileDO.getProcessedStoragePath();
        if (processedStoragePath == null || processedStoragePath.isEmpty()) {
            throw new BusinessException("请先处理文件");
        }
        // 读取配置
        MarkdownDocumentReaderConfig config = MarkdownDocumentReaderConfig.builder()
                // 水平线分割生成新文档
                .withHorizontalRuleCreateDocument(false)
                // 不包含代码块
                .withIncludeCodeBlock(false)
                // 不包含引用
                .withIncludeBlockquote(false)
                // 添加文件名元数据
                .withAdditionalMetadata("filename", fileDO.getName())
                .build();
        Resource resource = new UrlResource(fileStorage.getFileUrl(processedStoragePath));
        return new MarkdownDocumentReader(resource, config).get();
    }
}
