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
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RList;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class ChatMessageServiceImpl extends ServiceImpl<ChatMessageMapper, ChatMessage> implements ChatMessageService {

    @Resource
    private RedissonClient redissonClient;

    private static final String CHAT_MEMORY_KEY = "chat:memory:";
    private static final int CHAT_MEMORY_MAX_SIZE = 100;

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
    public List<ChatMessage> getRecentMessagesForMemory(Long conversationId, int limit) {
        // 优先从 Redis 读取
        if (redissonClient != null) {
            try {
                RList<Map<String, String>> list = redissonClient.getList(CHAT_MEMORY_KEY + conversationId);
                if (list.isExists() && list.size() > 0) {
                    int size = list.size();
                    int start = Math.max(0, size - limit);
                    List<Map<String, String>> entries = list.range(start, size);
                    List<ChatMessage> result = new ArrayList<>(entries.size());
                    for (int i = entries.size() - 1; i >= 0; i--) {
                        Map<String, String> entry = entries.get(i);
                        ChatMessage msg = new ChatMessage();
                        msg.setMessageType(entry.get("t"));
                        msg.setContent(entry.get("c"));
                        result.add(msg);
                    }
                    log.debug("从Redis读取chat memory: conversationId={}, size={}", conversationId, result.size());
                    return result;
                }
            } catch (Exception e) {
                log.warn("从Redis读取chat memory失败，回退到DB: conversationId={}", conversationId, e);
            }
        }
        // 缓存未命中，从 DB 加载并预热 Redis
        List<ChatMessage> allMessages = getSelf().findByConversationId(conversationId);
        warmUpMemoryCache(conversationId, allMessages);
        if (allMessages != null && !allMessages.isEmpty()) {
            int start = Math.max(0, allMessages.size() - limit);
            List<ChatMessage> recentMessages = allMessages.subList(start, allMessages.size());
            // 转为 DESC 顺序，与 findRecentByConversationId 保持一致
            List<ChatMessage> result = new ArrayList<>(recentMessages.size());
            for (int i = recentMessages.size() - 1; i >= 0; i--) {
                result.add(recentMessages.get(i));
            }
            return result;
        }
        return List.of();
    }

    @Override
    public ChatMessage saveUserMessage(Long conversationId,
                                       String content,
                                       String fileid,
                                       String fileIds) {
        ChatMessage msg = new ChatMessage();
        msg.setConversationId(conversationId);
        msg.setMessageType("user");
        msg.setContent(content);
        msg.setFileid(fileid);
        msg.setFileIds(fileIds);
        msg.setUseContext("1");
        msg.setDelFlag("0");
        msg.setCreateTime(LocalDateTime.now());
        save(msg);
        getSelf().evictByConversationId(conversationId);
        appendToMemoryCache(msg);
        return msg;
    }

    @Override
    public ChatMessage saveAssistantMessage(Long conversationId, Long replyId, String content,
                                            String thinking, String tools, String reference,
                                            String recommend, String knowledge, Long firstResponseTime,
                                            Long totalResponseTime, String fileIds) {
        ChatMessage msg = new ChatMessage();
        msg.setConversationId(conversationId);
        msg.setReplyId(replyId);
        msg.setMessageType("assistant");
        msg.setContent(content);
        msg.setThinking(thinking);
        msg.setTools(tools);
        msg.setReference(reference);
        msg.setRecommend(recommend);
        msg.setKnowledge(knowledge);
        msg.setFileid(null);
        msg.setFileIds(fileIds);
        msg.setFirstResponseTime(firstResponseTime);
        msg.setTotalResponseTime(totalResponseTime);
        msg.setUseContext("1");
        msg.setDelFlag("0");
        msg.setCreateTime(LocalDateTime.now());
        save(msg);
        getSelf().evictByConversationId(conversationId);
        appendToMemoryCache(msg);
        return msg;
    }

    @Override
    @CacheInvalidate(name = ":chatMessage:cache:", key = "#conversationId")
    public void evictByConversationId(Long conversationId) {
        log.debug("evictByConversationId[:chatMessage:cache:{}]", conversationId);
    }

    // ===== 内存缓存（Redis List）=====

    private void appendToMemoryCache(ChatMessage msg) {
        if (redissonClient == null || msg.getConversationId() == null) return;
        if (!"user".equals(msg.getMessageType()) && !"assistant".equals(msg.getMessageType())) return;
        try {
            RList<Map<String, String>> list = redissonClient.getList(CHAT_MEMORY_KEY + msg.getConversationId());
            Map<String, String> entry = new HashMap<>(4);
            entry.put("t", msg.getMessageType());
            entry.put("c", msg.getContent());
            list.add(entry);
            while (list.size() > CHAT_MEMORY_MAX_SIZE) {
                list.remove(0);
            }
            list.expire(1, TimeUnit.HOURS);
        } catch (Exception e) {
            log.warn("更新chat memory缓存失败: conversationId={}", msg.getConversationId(), e);
        }
    }

    private void warmUpMemoryCache(Long conversationId, List<ChatMessage> messages) {
        if (redissonClient == null || messages == null || messages.isEmpty()) return;
        try {
            RList<Map<String, String>> list = redissonClient.getList(CHAT_MEMORY_KEY + conversationId);
            if (list.isExists() && list.size() > 0) return; // 已有数据，不重复预热
            for (ChatMessage msg : messages) {
                if (!"user".equals(msg.getMessageType()) && !"assistant".equals(msg.getMessageType())) continue;
                if (list.size() >= CHAT_MEMORY_MAX_SIZE) list.remove(0);
                Map<String, String> entry = new HashMap<>(4);
                entry.put("t", msg.getMessageType());
                entry.put("c", msg.getContent());
                list.add(entry);
            }
            list.expire(1, TimeUnit.HOURS);
        } catch (Exception e) {
            log.warn("预热chat memory缓存失败: conversationId={}", conversationId, e);
        }
    }

    private ChatMessageService getSelf() {
        return SpringUtil.getBean(getClass());
    }
}
