package com.roydon.dear.knowledge.process.chain;

import com.alibaba.fastjson2.JSON;
import com.roydon.dear.knowledge.constant.MetadataKeyConstant;
import com.roydon.dear.knowledge.domain.entity.KnowledgeFileDO;
import com.roydon.dear.knowledge.domain.entity.KnowledgeFileSegmentDO;
import com.roydon.dear.knowledge.enums.FileSegmentStatus;
import com.roydon.dear.knowledge.enums.KnowledgeFileStatus;
import com.roydon.dear.knowledge.service.IKnowledgeFileSegmentService;
import com.roydon.dear.knowledge.service.IKnowledgeFileService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 步骤3：分段保存（异步）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SegmentSaveHandler extends AbstractFileProcessHandler {

    private final IKnowledgeFileSegmentService knowledgeFileSegmentService;
    private final IKnowledgeFileService knowledgeFileService;

    @Override
    @Async("fileProcessExecutor")
    public void handle(FileProcessContext ctx) {
        KnowledgeFileDO fileDO = ctx.getFileDO();
        List<Document> documentList = ctx.getDocuments();

        List<KnowledgeFileSegmentDO> knowledgeSegments = new ArrayList<>();
        for (int i = 0; i < documentList.size(); i++) {
            Document document = documentList.get(i);
            KnowledgeFileSegmentDO knowledgeSegment = new KnowledgeFileSegmentDO();
            knowledgeSegment.setFileId(fileDO.getId());
            knowledgeSegment.setText(document.getText());
            knowledgeSegment.setChunkId(String.valueOf((Long) document.getMetadata().get(MetadataKeyConstant.CHUNK_ID)));
//            knowledgeSegment.setBaseId(fileDO.getBaseId()); todo @roydon soon
            Map<String, Object> metadata = document.getMetadata();
            metadata.put(MetadataKeyConstant.BASE_ID, fileDO.getBaseId());
            metadata.put(MetadataKeyConstant.FILE_ID, fileDO.getId());
            metadata.put(MetadataKeyConstant.FILE_NAME, fileDO.getName());
            metadata.put(MetadataKeyConstant.PATH, fileDO.getStoragePath());
            knowledgeSegment.setMetadata(JSON.toJSONString(metadata));
            knowledgeSegment.setChunkOrder(i);
            knowledgeSegment.setStatus(FileSegmentStatus.CHUNKED);
            Integer skipEmbedding = (Integer) metadata.get(MetadataKeyConstant.SKIP_EMBEDDING);
            knowledgeSegment.setSkipEmbedding((skipEmbedding != null && skipEmbedding == 1) ? 1 : 0);
            knowledgeSegments.add(knowledgeSegment);
        }

        boolean saveSegmentResult = knowledgeFileSegmentService.saveBatch(knowledgeSegments);
        Assert.isTrue(saveSegmentResult, "保存知识片段失败");

        fileDO.setStatus(KnowledgeFileStatus.CHUNKED);
        boolean updateFileResult = knowledgeFileService.updateById(fileDO);
        Assert.isTrue(updateFileResult, "更新文件状态失败");

        processNext(ctx);
    }
}
