package com.roydon.dear.session.service.impl;

import cn.hutool.extra.spring.SpringUtil;
import com.alicp.jetcache.anno.CacheInvalidate;
import com.alicp.jetcache.anno.CacheRefresh;
import com.alicp.jetcache.anno.CacheType;
import com.alicp.jetcache.anno.Cached;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.roydon.dear.session.entity.ChatMessage;
import com.roydon.dear.session.mapper.ChatMessageMapper;
import com.roydon.dear.session.service.ChatMessageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class ChatMessageServiceImpl extends ServiceImpl<ChatMessageMapper, ChatMessage> implements ChatMessageService {

    @Override
    @Cached(name = ":chatMessage:cache:", key = "#conversationId", cacheType = CacheType.BOTH, cacheNullValue = true, expire = 60, localExpire = 10, timeUnit = TimeUnit.MINUTES)
    @CacheRefresh(refresh = 50, timeUnit = TimeUnit.MINUTES)
    public List<ChatMessage> findByConversationId(Long conversationId) {
        return lambdaQuery()
                .eq(ChatMessage::getConversationId, conversationId)
                .eq(ChatMessage::getDelFlag, "0")
                .orderByAsc(ChatMessage::getCreateTime)
                .list();
    }

    @Override
    public List<ChatMessage> findRecentByConversationId(Long conversationId, int limit) {
        return lambdaQuery()
                .eq(ChatMessage::getConversationId, conversationId)
                .eq(ChatMessage::getDelFlag, "0")
                .orderByDesc(ChatMessage::getCreateTime)
                .last("LIMIT " + limit)
                .list();
    }

    @Override
    public ChatMessage saveUserMessage(Long conversationId, String content, String fileid) {
        ChatMessage msg = new ChatMessage();
        msg.setConversationId(conversationId);
        msg.setMessageType("user");
        msg.setContent(content);
        msg.setFileid(fileid);
        msg.setUseContext("1");
        msg.setDelFlag("0");
        msg.setCreateTime(LocalDateTime.now());
        save(msg);
        getSelf().evictByConversationId(conversationId);
        return msg;
    }

    @Override
    public ChatMessage saveAssistantMessage(Long conversationId, Long replyId, String content,
                                            String thinking, String tools, String reference,
                                            String recommend, Long firstResponseTime,
                                            Long totalResponseTime) {
        ChatMessage msg = new ChatMessage();
        msg.setConversationId(conversationId);
        msg.setReplyId(replyId);
        msg.setMessageType("assistant");
        msg.setContent(content);
        msg.setThinking(thinking);
        msg.setTools(tools);
        msg.setReference(reference);
        msg.setRecommend(recommend);
        msg.setFileid(null);
        msg.setFirstResponseTime(firstResponseTime);
        msg.setTotalResponseTime(totalResponseTime);
        msg.setUseContext("1");
        msg.setDelFlag("0");
        msg.setCreateTime(LocalDateTime.now());
        save(msg);
        getSelf().evictByConversationId(conversationId);
        return msg;
    }

    @Override
    @CacheInvalidate(name = ":chatMessage:cache:", key = "#conversationId")
    public void evictByConversationId(Long conversationId) {
        // 仅触发缓存失效
        log.debug("evictByConversationId[:chatMessage:cache:{}]", conversationId);
    }

    // 获取自身的bean，用于aop失效
    private ChatMessageService getSelf() {
        return SpringUtil.getBean(getClass());
    }
}
