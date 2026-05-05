package com.roydon.dear.session.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.roydon.dear.session.entity.AiSession;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AiSessionMapper extends BaseMapper<AiSession> {

    @Select("""
            SELECT s1.* FROM ai_session s1
            WHERE s1.id = (SELECT s2.id FROM ai_session s2 WHERE s2.session_id = s1.session_id ORDER BY s2.create_time ASC LIMIT 1)
            ORDER BY s1.update_time DESC
            """)
    IPage<AiSession> selectSessionListWithFirstRecord(Page<AiSession> page);
}
