package com.roydon.dear.knowledge.process;

import com.roydon.dear.common.lock.DistributeLock;
import com.roydon.dear.knowledge.domain.entity.KnowledgeFileDO;
import com.roydon.dear.knowledge.process.chain.AbstractFileProcessHandler;
import com.roydon.dear.knowledge.process.chain.EmbeddingHandler;
import com.roydon.dear.knowledge.process.chain.FileConvertHandler;
import com.roydon.dear.knowledge.process.chain.FileProcessContext;
import com.roydon.dear.knowledge.process.chain.FileSplitHandler;
import com.roydon.dear.knowledge.process.chain.SegmentSaveHandler;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import static com.roydon.dear.knowledge.constant.LockSceneConstant.FILE_PROCESS_LOCK;

/**
 * 文件处理入口 —— 组装责任链并对外暴露处理入口。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FileProcessComponent {

    private final FileConvertHandler fileConvertHandler;
    private final FileSplitHandler fileSplitHandler;
    private final SegmentSaveHandler segmentSaveHandler;
    private final EmbeddingHandler embeddingHandler;

    private AbstractFileProcessHandler chainHead;

    @PostConstruct
    public void init() {
        fileConvertHandler.setNext(fileSplitHandler);
        fileSplitHandler.setNext(segmentSaveHandler);
        segmentSaveHandler.setNext(embeddingHandler);
        this.chainHead = fileConvertHandler;
    }

    @DistributeLock(scene = FILE_PROCESS_LOCK, keyExpression = "#fileDO.id", waitTime = 0)
    public void processFile(KnowledgeFileDO fileDO) {
        chainHead.handle(new FileProcessContext(fileDO));
    }
}
