package com.roydon.dear.knowledge.process;

import com.alibaba.fastjson2.JSON;
import com.esotericsoftware.minlog.Log;
import com.roydon.dear.common.exception.BusinessException;
import com.roydon.dear.common.lock.DistributeLock;
import com.roydon.dear.core.service.FileStorage;
import com.roydon.dear.knowledge.constant.MetadataKeyConstant;
import com.roydon.dear.knowledge.domain.bo.FileSplitBO;
import com.roydon.dear.knowledge.domain.entity.KnowledgeFileDO;
import com.roydon.dear.knowledge.domain.entity.KnowledgeFileSegmentDO;
import com.roydon.dear.knowledge.enums.FileMineType;
import com.roydon.dear.knowledge.enums.FileSegmentStatus;
import com.roydon.dear.knowledge.enums.FileSplitType;
import com.roydon.dear.knowledge.enums.KnowledgeFileStatus;
import com.roydon.dear.knowledge.rag.reader.DocumentReaderFactory;
import com.roydon.dear.knowledge.rag.splitter.FileSplitter;
import com.roydon.dear.knowledge.rag.splitter.FileSplitterFactory;
import com.roydon.dear.knowledge.service.IKnowledgeFileSegmentService;
import com.roydon.dear.knowledge.service.IKnowledgeFileService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.xwpf.usermodel.TextSegment;
import org.springframework.ai.document.Document;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.roydon.dear.knowledge.constant.LockSceneConstant.FILE_PROCESS_LOCK;

/**
 * 文件处理组合组件
 * 组合模式，将文件处理策略组合起来
 *
 * @AUTHOR: roydon
 * @DATE: 2026/5/23
 **/
@Slf4j
@Component
@RequiredArgsConstructor
public class FileProcessComponent {
    private final FileProcessStrategyFactory fileProcessStrategyFactory;
    private final DocumentReaderFactory documentReaderFactory;
    private final IKnowledgeFileSegmentService knowledgeFileSegmentService;
    private final IKnowledgeFileService knowledgeFileService;
    private final FileStorage fileStorage;

    @Async
    @DistributeLock(scene = FILE_PROCESS_LOCK, keyExpression = "#fileDO.id", waitTime = 0)
    public void processFile(KnowledgeFileDO fileDO) {
        // 1、文件处理
        FileProcessStrategy fileProcessStrategy = fileProcessStrategyFactory.get(FileMineType.fromValue(fileDO.getMineType()));
        if (fileProcessStrategy == null) {
            throw new BusinessException("暂不支持该文件类型");
        }
        KnowledgeFileDO processedFileDO = fileProcessStrategy.processFile(fileDO);
        // 2、文件切分
        FileSplitter fileSplitter = FileSplitterFactory.getInstance(FileSplitBO.builder()
                .splitType(FileSplitType.SMART)
                .chunkSize(500)
                .overlap(100)
                .build());
        if (fileSplitter == null) {
            throw new BusinessException("暂不支持该文件类型");
        }
        List<Document> documentList = new ArrayList<>();
        try {
            documentList = fileSplitter.split(new Document(new String(fileStorage.downloadFile(processedFileDO.getProcessedStoragePath()).readAllBytes(), StandardCharsets.UTF_8)));
        } catch (Exception e) {
            log.debug("文件切分失败");
            return;
        }
        log.debug("文件切分成功，共{}个分段", documentList.size());
        // 3、切分分段文件保存
        List<KnowledgeFileSegmentDO> knowledgeSegments = new ArrayList<>();
        for (int i = 0; i < documentList.size(); i++) {
            Document document = documentList.get(i);
            KnowledgeFileSegmentDO knowledgeSegment = new KnowledgeFileSegmentDO();
            knowledgeSegment.setFileId(processedFileDO.getId());
            knowledgeSegment.setText(document.getText());
            knowledgeSegment.setChunkId(String.valueOf((Long) document.getMetadata().get(MetadataKeyConstant.CHUNK_ID)));
            Map<String, Object> metadata = document.getMetadata();
            metadata.put(MetadataKeyConstant.FILE_ID, processedFileDO.getId());
            metadata.put(MetadataKeyConstant.FILE_NAME, processedFileDO.getName());
            metadata.put(MetadataKeyConstant.PATH, processedFileDO.getStoragePath());
            // todo metadata统一处理(权限相关、多版本相关）
            knowledgeSegment.setMetadata(JSON.toJSONString(metadata));
            knowledgeSegment.setChunkOrder(i);
            knowledgeSegment.setStatus(FileSegmentStatus.CHUNKED);
            // 检查是否需要跳过嵌入
            Integer skipEmbedding = (Integer) metadata.get(MetadataKeyConstant.SKIP_EMBEDDING);
            knowledgeSegment.setSkipEmbedding((skipEmbedding != null && skipEmbedding == 1) ? 1 : 0);
            knowledgeSegments.add(knowledgeSegment);
        }
        boolean saveSegmentResult = knowledgeFileSegmentService.saveBatch(knowledgeSegments);
        Assert.isTrue(saveSegmentResult, "保存知识片段失败");
        // 更新文档状态为 CHUNKED
        processedFileDO.setStatus(KnowledgeFileStatus.CHUNKED);
        boolean updateFileResult = knowledgeFileService.updateById(processedFileDO);
        Assert.isTrue(updateFileResult, "更新文件状态失败");
        // todo 4、进行向量化

    }

}
