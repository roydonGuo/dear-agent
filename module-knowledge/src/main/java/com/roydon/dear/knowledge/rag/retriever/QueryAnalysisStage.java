package com.roydon.dear.knowledge.rag.retriever;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.roydon.dear.knowledge.domain.entity.KnowledgeFileDO;
import com.roydon.dear.knowledge.service.IKnowledgeFileService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 查询分析阶段：将 knowledgeBaseIds 解析为 ES filterExpression（按 fileId 过滤）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QueryAnalysisStage implements RagStage {

    private final IKnowledgeFileService knowledgeFileService;

    @Override
    public void execute(RagContext ctx) {
        List<Long> knowledgeBaseIds = ctx.getKnowledgeBaseIds();
        if (CollectionUtils.isEmpty(knowledgeBaseIds)) {
            return;
        }

        List<KnowledgeFileDO> files = knowledgeFileService.list(
                new LambdaQueryWrapper<KnowledgeFileDO>()
                        .in(KnowledgeFileDO::getBaseId, knowledgeBaseIds)
                        .select(KnowledgeFileDO::getId));

        if (CollectionUtils.isEmpty(files)) {
            log.debug("未找到知识库对应的文件: knowledgeBaseIds={}", knowledgeBaseIds);
            return;
        }

        List<Long> fileIds = files.stream()
                .map(KnowledgeFileDO::getId)
                .distinct()
                .toList();

        ctx.setResolvedFileIds(fileIds);

        String fileIdList = fileIds.stream()
                .map(id -> "'" + id + "'")
                .collect(Collectors.joining(", "));

        ctx.setFilterExpression("fileId in [" + fileIdList + "]");
        log.debug("查询分析完成: knowledgeBaseIds={}, resolvedFileIds={}, filterExpression={}",
                knowledgeBaseIds, fileIds.size(), ctx.getFilterExpression());
    }
}
