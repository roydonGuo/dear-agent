package com.roydon.dear.session.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.roydon.dear.session.entity.ChatConversation;

public interface ChatConversationService extends IService<ChatConversation> {

    ChatConversation getBySessionId(String sessionId);

    ChatConversation getOrCreateBySessionId(String sessionId, String title);

    boolean updateLastMessage(Long conversationId, String lastMessage);

    void evictBySessionId(String sessionId);
}
