package com.roydon.dear.web.controller;

import com.roydon.dear.knowledge.domain.entity.convertor.KnowledgeFileConvertor;
import com.roydon.dear.knowledge.service.IKnowledgeFileService;
import io.micrometer.core.annotation.Timed;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@Validated
@Tag(name = "知识库文件分段处理", description = "知识库文件分段/向量化/存储接口")
@RestController
@RequestMapping("/knowledge-file-segment")
@RequiredArgsConstructor
public class KnowledgeFileSegmentController {

    private final IKnowledgeFileService knowledgeFileService;
    private final KnowledgeFileConvertor knowledgeFileConvertor;


    @Timed(value = "kf-segment.process", description = "Process file segments")
    @PostMapping("/process-file")
    public String processFile(Long fileId) {

        return "处理成功";
    }

}
