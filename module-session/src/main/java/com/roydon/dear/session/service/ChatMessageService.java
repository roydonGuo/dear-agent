package com.roydon.dear.session.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.roydon.dear.session.entity.ChatMessage;

import java.util.List;

public interface ChatMessageService extends IService<ChatMessage> {

    List<ChatMessage> findByConversationId(Long conversationId);

    List<ChatMessage> findRecentByConversationId(Long conversationId, int limit);

    ChatMessage saveUserMessage(Long conversationId, String content, String fileid);

    ChatMessage saveAssistantMessage(Long conversationId, Long replyId, String content,
                                     String thinking, String tools, String reference,
                                     String recommend, Long firstResponseTime,
                                     Long totalResponseTime);

    void evictByConversationId(Long conversationId);
}
