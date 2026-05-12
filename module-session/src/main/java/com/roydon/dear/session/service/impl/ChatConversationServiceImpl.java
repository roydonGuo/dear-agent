package com.roydon.dear.session.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.roydon.dear.session.entity.ChatConversation;
import com.roydon.dear.session.mapper.ChatConversationMapper;
import com.roydon.dear.session.service.ChatConversationService;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
public class ChatConversationServiceImpl extends ServiceImpl<ChatConversationMapper, ChatConversation> implements ChatConversationService {

    @Override
    public ChatConversation getBySessionId(String sessionId) {
        return lambdaQuery()
                .eq(ChatConversation::getSessionId, sessionId)
                .eq(ChatConversation::getDelFlag, "0")
                .one();
    }

    @Override
    public ChatConversation getOrCreateBySessionId(String sessionId, String title) {
        ChatConversation conv = getBySessionId(sessionId);
        if (conv != null) return conv;

        conv = new ChatConversation();
        conv.setSessionId(sessionId);
        conv.setTitle(title);
        conv.setUserId(0L);
        conv.setPinned("0");
        conv.setDelFlag("0");
        conv.setTemperature(0.75);
        conv.setMaxTokens(4096);
        conv.setMaxContexts(20);
        conv.setCreateTime(LocalDateTime.now());
        conv.setUpdateTime(LocalDateTime.now());
        save(conv);
        return conv;
    }

    @Override
    public boolean updateLastMessage(Long conversationId, String lastMessage) {
        return lambdaUpdate()
                .eq(ChatConversation::getId, conversationId)
                .set(ChatConversation::getLastMessage, lastMessage)
                .update();
    }
}
