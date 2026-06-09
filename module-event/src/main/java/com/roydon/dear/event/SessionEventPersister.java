package com.roydon.dear.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.roydon.dear.event.events.DoneEvent;
import com.roydon.dear.event.events.ErrorEvent;
import com.roydon.dear.session.service.ChatMessageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

public class SessionEventPersister {

    private static final Logger log = LoggerFactory.getLogger(SessionEventPersister.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ChatMessageService messageService;

    public SessionEventPersister(ChatMessageService messageService) {
        this.messageService = messageService;
    }

    public void attach(AgentEventBus bus, Long conversationId, Long replyId) {
        List<AgentEvent> allEvents = new ArrayList<>();

        bus.onAll(allEvents::add);

        bus.on(DoneEvent.class, e -> {
            try {
                String eventStreamJson = MAPPER.writeValueAsString(allEvents);
                messageService.saveAssistantMessage(conversationId, replyId, eventStreamJson);
            } catch (JsonProcessingException ex) {
                log.error("序列化 event_stream 失败: conversationId={}", conversationId, ex);
            }
        });

        bus.on(ErrorEvent.class, e -> {
            try {
                String eventStreamJson = MAPPER.writeValueAsString(allEvents);
                messageService.saveAssistantMessage(conversationId, replyId, eventStreamJson);
            } catch (JsonProcessingException ex) {
                log.error("序列化 event_stream 失败: conversationId={}", conversationId, ex);
            }
        });
    }
}
