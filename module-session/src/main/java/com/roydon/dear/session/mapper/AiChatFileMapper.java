package com.roydon.dear.session.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.roydon.dear.session.entity.AiChatFile;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.springframework.data.domain.Pageable;
import java.util.List;

/**
 * 文件元数据表，存储文件基本信息和解析后的内容(AiChatFile)表数据库访问层
 *
 * @author roydon
 * @since 2026-05-20 21:41:50
 */
@Mapper
public interface AiChatFileMapper extends BaseMapper<AiChatFile>{



}

