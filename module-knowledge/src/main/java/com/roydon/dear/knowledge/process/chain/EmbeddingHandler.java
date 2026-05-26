package com.roydon.dear.knowledge.process.chain;

import com.roydon.dear.knowledge.process.EmbedProcess;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * 步骤4：向量化存储（异步）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EmbeddingHandler extends AbstractFileProcessHandler {

    private final EmbedProcess embedProcess;

    @Override
    @Async("fileProcessExecutor")
    public void handle(FileProcessContext ctx) {
        embedProcess.embedAndStore(ctx.getFileDO());
        processNext(ctx);
    }
}
