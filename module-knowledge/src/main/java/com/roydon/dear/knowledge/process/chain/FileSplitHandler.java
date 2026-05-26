package com.roydon.dear.knowledge.process.chain;

import com.roydon.dear.common.exception.BusinessException;
import com.roydon.dear.core.service.FileStorage;
import com.roydon.dear.knowledge.domain.bo.FileSplitBO;
import com.roydon.dear.knowledge.enums.FileSplitType;
import com.roydon.dear.knowledge.rag.splitter.FileSplitter;
import com.roydon.dear.knowledge.rag.splitter.FileSplitterFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 步骤2：文档分片（同步）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FileSplitHandler extends AbstractFileProcessHandler {

    private final FileStorage fileStorage;

    @Override
    public void handle(FileProcessContext ctx) {
        FileSplitter fileSplitter = FileSplitterFactory.getInstance(FileSplitBO.builder()
                .splitType(FileSplitType.SMART)
                .chunkSize(500)
                .overlap(50)
                .build());
        if (fileSplitter == null) {
            throw new BusinessException("暂不支持该文件类型");
        }
        List<Document> documentList = new ArrayList<>();
        try {
            documentList = fileSplitter.split(new Document(
                    new String(fileStorage.downloadFile(ctx.getFileDO().getProcessedStoragePath()).readAllBytes(), StandardCharsets.UTF_8)));
        } catch (Exception e) {
            log.debug("文件切分失败");
            return;
        }
        log.debug("文件切分成功，共{}个分段", documentList.size());
        ctx.setDocuments(documentList);
        processNext(ctx);
    }
}
