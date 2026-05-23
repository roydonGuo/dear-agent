package com.roydon.dear.knowledge.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.roydon.dear.knowledge.domain.entity.KnowledgeFileSegmentDO;
import com.roydon.dear.knowledge.mapper.KnowledgeFileSegmentMapper;
import com.roydon.dear.knowledge.service.IKnowledgeFileSegmentService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;


/**
 * knowledge-文件片段表(KnowledgeFileSegment)表服务实现类
 *
 * @author roydon
 * @since 2026-05-23 18:35:33
 */
@Service
@RequiredArgsConstructor
public class KnowledgeFileSegmentServiceImpl extends ServiceImpl<KnowledgeFileSegmentMapper, KnowledgeFileSegmentDO> implements IKnowledgeFileSegmentService {
    private final KnowledgeFileSegmentMapper knowledgeFileSegmentMapper;

}
