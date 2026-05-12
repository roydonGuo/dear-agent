package com.roydon.dear.session.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.roydon.dear.session.entity.ChatMessage;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ChatMessageMapper extends BaseMapper<ChatMessage> {
}
