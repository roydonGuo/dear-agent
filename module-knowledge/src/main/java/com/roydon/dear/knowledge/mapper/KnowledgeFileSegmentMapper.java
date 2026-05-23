package com.roydon.dear.knowledge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.roydon.dear.knowledge.domain.entity.KnowledgeFileSegmentDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.springframework.data.domain.Pageable;

import java.util.List;

/**
 * knowledge-文件片段表(KnowledgeFileSegment)表数据库访问层
 *
 * @author roydon
 * @since 2026-05-23 18:35:32
 */
@Mapper
public interface KnowledgeFileSegmentMapper extends BaseMapper<KnowledgeFileSegmentDO> {

}

