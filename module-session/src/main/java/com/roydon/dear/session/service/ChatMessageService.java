package com.roydon.dear.session.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.roydon.dear.session.entity.ChatMessage;

import java.util.List;

public interface ChatMessageService extends IService<ChatMessage> {

    List<ChatMessage> findByConversationId(Long conversationId);

    List<ChatMessage> findRecentByConversationId(Long conversationId, int limit);

    /**
     * 从 Redis 缓存获取最近消息（用于构建 ChatMemory），缓存未命中时回退到 DB 并预热缓存
     */
    List<ChatMessage> getRecentMessagesForMemory(Long conversationId, int limit);

    ChatMessage saveUserMessage(Long conversationId,
                                String content,
                                String fileid,
                                String fileIds);

    @Deprecated
    ChatMessage saveAssistantMessage(Long conversationId,
                                     Long replyId,
                                     String content,
                                     String thinking,
                                     String tools,
                                     String reference,
                                     String recommend,
                                     String knowledge,
                                     Long firstResponseTime,
                                     Long totalResponseTime,
                                     String fileIds);

    /** 新接口：仅存储 event_stream 完整事件数组，旧字段不再写入 */
    ChatMessage saveAssistantMessage(Long conversationId, Long replyId, String eventStream);

    void evictByConversationId(Long conversationId);
}
