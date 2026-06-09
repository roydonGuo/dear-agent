package com.roydon.dear.event.config;

import com.roydon.dear.event.AgentEventBus;
import com.roydon.dear.event.DefaultAgentEventBus;
import com.roydon.dear.event.SessionEventPersister;
import com.roydon.dear.event.SseEventEmitter;
import com.roydon.dear.session.service.ChatMessageService;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;

@Configuration
public class EventBusConfiguration {

    @Bean
    @Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
    public AgentEventBus agentEventBus() {
        return new DefaultAgentEventBus();
    }

    @Bean
    @Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
    public SseEventEmitter sseEventEmitter(AgentEventBus eventBus) {
        return new SseEventEmitter(eventBus);
    }

    @Bean
    @Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
    public SessionEventPersister sessionEventPersister(ChatMessageService messageService) {
        return new SessionEventPersister(messageService);
    }
}
