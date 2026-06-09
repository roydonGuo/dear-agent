package com.roydon.dear.agent.orchestrator;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.roydon.dear.agent.DearAgent;
import com.roydon.dear.agent.registry.AgentRegistry;
import com.roydon.dear.common.AgentResponse;
import com.roydon.dear.common.manager.AgentTaskManager;
import com.roydon.dear.event.AgentEventBus;
import com.roydon.dear.event.events.AgentDoneEvent;
import com.roydon.dear.event.events.AgentErrorEvent;
import com.roydon.dear.event.events.AgentStartEvent;
import com.roydon.dear.knowledge.rag.retriever.KnowledgeRetrievalService;
import com.roydon.dear.session.service.ChatConversationService;
import com.roydon.dear.session.service.ChatMessageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Phase 1 编排器：继承 DearAgent 的全部能力，
 * 在其工具列表中注入已注册的 Agent 工具。
 */
@Slf4j
public class SimpleOrchestrator extends DearAgent {

    private final AgentRegistry agentRegistry;

    private SimpleOrchestrator(Builder builder) {
        super(builder.name, builder.chatModel,
                mergeTools(builder.tools, builder.agentRegistry),
                builder.systemPrompt, builder.maxRounds,
                builder.chatMemory, builder.advisors, builder.maxReflectionRounds,
                builder.conversationService, builder.messageService,
                builder.taskManager, builder.knowledgeRetrievalService);
        this.agentRegistry = builder.agentRegistry;
    }

    private static List<ToolCallback> mergeTools(
            List<ToolCallback> normalTools, AgentRegistry registry) {
        List<ToolCallback> all = new ArrayList<>(normalTools);
        if (registry != null) {
            all.addAll(registry.getAgentTools());
        }
        return all;
    }

    /**
     * 覆盖工具启动事件发射 — 检测是否为 Agent 工具，发射相应事件
     */
    @Override
    protected void publishToolStart(String id, String toolName, java.util.Map<String, Object> input) {
        if (agentRegistry != null && agentRegistry.getAgentNames().contains(toolName)) {
            setupSubAgentContext();

            String task = extractTask(input);
            if (eventBus != null) {
                eventBus.publish(new AgentStartEvent(toolName, task));
            }
            return;
        }
        super.publishToolStart(id, toolName, input);
    }

    @Override
    protected void publishToolEnd(String id, String toolName, String result) {
        if (agentRegistry != null && agentRegistry.getAgentNames().contains(toolName)) {
            SubAgentContext.clear();

            if (eventBus != null) {
                eventBus.publish(new AgentDoneEvent(toolName, result));
            }
            return;
        }
        super.publishToolEnd(id, toolName, result);
    }

    @Override
    protected void publishToolError(String id, String toolName, String error) {
        if (agentRegistry != null && agentRegistry.getAgentNames().contains(toolName)) {
            SubAgentContext.clear();

            if (eventBus != null) {
                eventBus.publish(new AgentErrorEvent(toolName, error));
            }
            return;
        }
        super.publishToolError(id, toolName, error);
    }

    private String extractTask(java.util.Map<String, Object> input) {
        if (input == null) return "";
        Object task = input.get("task");
        if (task instanceof String s) return s;
        return JSON.toJSONString(input);
    }

    private void setupSubAgentContext() {
        // 通过 eventBus 传递上下文，不再依赖 currentSink
        SubAgentContext ctx = new SubAgentContext(
                currentConversationId != null ? currentConversationId : "",
                null, // sink 不再可用，子 Agent 通过 eventBus 沟通
                currentConversationNumericId,
                currentUserMessageId,
                conversationService,
                messageService,
                null);
        SubAgentContext.set(ctx);
    }

    public static Builder orchestratorBuilder() {
        return new Builder();
    }

    public static class Builder {
        private String name;
        private ChatModel chatModel;
        private List<ToolCallback> tools = List.of();
        private String systemPrompt = "";
        private int maxReflectionRounds;
        private int maxRounds = 30;
        private List<Advisor> advisors;
        private ChatMemory chatMemory;
        private ChatConversationService conversationService;
        private ChatMessageService messageService;
        private AgentTaskManager taskManager;
        private KnowledgeRetrievalService knowledgeRetrievalService;
        private AgentRegistry agentRegistry;
        private AgentEventBus eventBus;

        public Builder name(String name) { this.name = name; return this; }
        public Builder chatModel(ChatModel chatModel) { this.chatModel = chatModel; return this; }
        public Builder tools(ToolCallback... tools) { this.tools = Arrays.asList(tools); return this; }
        public Builder tools(List<ToolCallback> tools) { this.tools = tools; return this; }
        public Builder systemPrompt(String systemPrompt) { this.systemPrompt = systemPrompt; return this; }
        public Builder maxRounds(int maxRounds) { this.maxRounds = maxRounds; return this; }
        public Builder maxReflectionRounds(int maxReflectionRounds) { this.maxReflectionRounds = maxReflectionRounds; return this; }
        public Builder advisors(List<Advisor> advisors) { this.advisors = advisors; return this; }
        public Builder advisors(Advisor... advisors) { this.advisors = Arrays.asList(advisors); return this; }
        public Builder chatMemory(ChatMemory chatMemory) { this.chatMemory = chatMemory; return this; }
        public Builder conversationService(ChatConversationService conversationService) { this.conversationService = conversationService; return this; }
        public Builder messageService(ChatMessageService messageService) { this.messageService = messageService; return this; }
        public Builder taskManager(AgentTaskManager taskManager) { this.taskManager = taskManager; return this; }
        public Builder knowledgeRetrievalService(KnowledgeRetrievalService knowledgeRetrievalService) { this.knowledgeRetrievalService = knowledgeRetrievalService; return this; }
        public Builder agentRegistry(AgentRegistry agentRegistry) { this.agentRegistry = agentRegistry; return this; }
        public Builder eventBus(AgentEventBus eventBus) { this.eventBus = eventBus; return this; }

        public SimpleOrchestrator build() {
            if (chatModel == null) throw new IllegalArgumentException("chatModel 不能为空！");
            if (agentRegistry == null) throw new IllegalArgumentException("agentRegistry 不能为空！");
            SimpleOrchestrator orchestrator = new SimpleOrchestrator(this);
            if (eventBus != null) orchestrator.setEventBus(eventBus);
            return orchestrator;
        }
    }
}
