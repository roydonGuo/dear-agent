package com.roydon.dear.knowledge.process.chain;

import com.roydon.dear.common.exception.BusinessException;
import com.roydon.dear.knowledge.domain.entity.KnowledgeFileDO;
import com.roydon.dear.knowledge.enums.FileMineType;
import com.roydon.dear.knowledge.process.FileProcessStrategy;
import com.roydon.dear.knowledge.process.FileProcessStrategyFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 步骤1：文件格式转换（同步）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FileConvertHandler extends AbstractFileProcessHandler {

    private final FileProcessStrategyFactory fileProcessStrategyFactory;

    @Override
    public void handle(FileProcessContext ctx) {
        FileProcessStrategy strategy = fileProcessStrategyFactory.get(FileMineType.fromValue(ctx.getFileDO().getMineType()));
        if (strategy == null) {
            throw new BusinessException("暂不支持该文件类型");
        }
        KnowledgeFileDO processedFileDO = strategy.processFile(ctx.getFileDO());
        ctx.setFileDO(processedFileDO);
        processNext(ctx);
    }
}
