package com.roydon.dear.session.service.impl;

import com.alicp.jetcache.anno.CacheInvalidate;
import com.alicp.jetcache.anno.CacheRefresh;
import com.alicp.jetcache.anno.CacheType;
import com.alicp.jetcache.anno.Cached;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.roydon.dear.session.entity.ChatConversation;
import com.roydon.dear.session.mapper.ChatConversationMapper;
import com.roydon.dear.session.service.ChatConversationService;
import org.springframework.stereotype.Service;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

@Service
public class ChatConversationServiceImpl extends ServiceImpl<ChatConversationMapper, ChatConversation> implements ChatConversationService {

    @Override
    @Cached(name = ":chatConversation:cache:", key = "#sessionId", cacheType = CacheType.BOTH, cacheNullValue = true,  expire = 60, localExpire = 10, timeUnit = TimeUnit.MINUTES)
    @CacheRefresh(refresh = 50, timeUnit = TimeUnit.MINUTES)
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

    @Override
    @CacheInvalidate(name = ":conversation:cache:", key = "#sessionId")
    public void evictBySessionId(String sessionId) {
        // 仅触发缓存失效
    }

    @Override
    public boolean updateById(ChatConversation entity) {
        boolean result = super.updateById(entity);
        if (result && entity.getSessionId() != null) {
            evictBySessionId(entity.getSessionId());
        }
        return result;
    }

    @Override
    public boolean removeById(Serializable id) {
        ChatConversation conv = getById(id);
        boolean result = super.removeById(id);
        if (result && conv != null && conv.getSessionId() != null) {
            evictBySessionId(conv.getSessionId());
        }
        return result;
    }
}
