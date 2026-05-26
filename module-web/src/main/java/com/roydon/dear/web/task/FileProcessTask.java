package com.roydon.dear.web.task;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.roydon.dear.knowledge.domain.entity.KnowledgeFileDO;
import com.roydon.dear.knowledge.domain.entity.KnowledgeFileSegmentDO;
import com.roydon.dear.knowledge.enums.FileSegmentStatus;
import com.roydon.dear.knowledge.enums.KnowledgeFileStatus;
import com.roydon.dear.knowledge.process.EmbedProcess;
import com.roydon.dear.knowledge.service.IKnowledgeFileSegmentService;
import com.roydon.dear.knowledge.service.IKnowledgeFileService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 文件向量化定时补偿任务
 *
 * @author roydon
 * @since 2026/5/26
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FileProcessTask {

    private final IKnowledgeFileService knowledgeFileService;
    private final IKnowledgeFileSegmentService knowledgeSegmentService;
    private final EmbedProcess embedProcess;

    /** 每次调度最多处理的文件数量 */
    private static final int MAX_FILES_PER_RUN = 5;
    /** 文件间间隔（毫秒），缓解向量存储写入压力 */
    private static final int INTERVAL_MS = 3000;

    /**
     * 每10分钟扫描一次，补偿向量化未完成的文件。
     * 使用 fileProcessExecutor 线程池异步执行，不阻塞调度线程。
     */
    @Async("fileProcessExecutor")
    @Scheduled(cron = "0 0/10 * * * ?")
    public void processFile() {
        log.info("开始执行文件向量化补偿任务");

        List<KnowledgeFileDO> chunkedFiles = knowledgeFileService.list(
                Wrappers.<KnowledgeFileDO>lambdaQuery()
                        .eq(KnowledgeFileDO::getStatus, KnowledgeFileStatus.CHUNKED)
                        .last("LIMIT " + MAX_FILES_PER_RUN));

        if (chunkedFiles.isEmpty()) {
            return;
        }

        log.info("发现 {} 个待补偿的文件（上限 {}）", chunkedFiles.size(), MAX_FILES_PER_RUN);

        int success = 0;
        int fail = 0;
        for (int i = 0; i < chunkedFiles.size(); i++) {
            KnowledgeFileDO file = chunkedFiles.get(i);
            Long fileId = file.getId();

            try {
                boolean ok = embedProcess.embedAndStore(file);
                if (ok) {
                    success++;
                } else {
                    ensureFileStatus(fileId);
                    fail++;
                }
            } catch (Exception e) {
                log.error("补偿文件 {} 向量化异常: {}", fileId, e.getMessage(), e);
                ensureFileStatus(fileId);
                fail++;
            }

            if (i < chunkedFiles.size() - 1) {
                try {
                    Thread.sleep(INTERVAL_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        log.info("文件向量化补偿任务完成，成功: {}，失败: {}，下次继续补偿", success, fail);
    }

    /**
     * 防御性检查：若文件已无 CHUNKED 状态的分段，将文件状态修正为 VECTOR_STORED。
     */
    private void ensureFileStatus(Long fileId) {
        long remaining = knowledgeSegmentService.count(
                Wrappers.<KnowledgeFileSegmentDO>lambdaQuery()
                        .eq(KnowledgeFileSegmentDO::getFileId, fileId)
                        .eq(KnowledgeFileSegmentDO::getStatus, FileSegmentStatus.CHUNKED)
                        .eq(KnowledgeFileSegmentDO::getSkipEmbedding, 0));

        if (remaining == 0) {
            KnowledgeFileDO fileDO = knowledgeFileService.getById(fileId);
            if (fileDO != null && fileDO.getStatus() == KnowledgeFileStatus.CHUNKED) {
                fileDO.setStatus(KnowledgeFileStatus.VECTOR_STORED);
                knowledgeFileService.updateById(fileDO);
                log.info("文件 {} 状态已修正为 VECTOR_STORED", fileId);
            }
        }
    }
}
