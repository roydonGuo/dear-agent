package com.roydon.dear.agent.orchestrator;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.roydon.dear.agent.DearAgent;
import com.roydon.dear.agent.registry.AgentRegistry;
import com.roydon.dear.common.AgentResponse;
import com.roydon.dear.common.manager.AgentTaskManager;
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
 *
 * 对 LLM 来说，调用一个 Sub-Agent 和调用普通 Tool 没有区别 —
 * 但 AgentToolAdapter 内部会走完整的 BaseAgent 执行流程。
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
     * 覆盖工具状态 SSE 发射 — Sub-Agent 调用发射 agent_call/agent_start/agent_done
     */
    @Override
    protected String emitToolStatus(String toolType, String content) {
        // 解析 content 中的 tool name，判断是否为 Agent 工具
        String toolName = extractToolName(content);
        if (toolName != null && agentRegistry.getAgentNames().contains(toolName)) {
            return emitAgentStatus(toolType, toolName, content);
        }
        return super.emitToolStatus(toolType, content);
    }

    private String extractToolName(String content) {
        try {
            JSONObject json = JSON.parseObject(content);
            return json.getString("tool");
        } catch (Exception e) {
            return null;
        }
    }

    private String emitAgentStatus(String toolType, String toolName, String content) {
        try {
            JSONObject json = JSON.parseObject(content);
            String status = json.getString("status");
            if ("start".equals(status)) {
                // 设置 SubAgentContext，让子 Agent 走流式输出 + 父会话持久化路径
                setupSubAgentContext();

                JSONObject agentMsg = new JSONObject();
                agentMsg.put("agent", toolName);
                agentMsg.put("status", "start");
                String args = json.getString("args");
                if (args != null) {
                    try {
                        JSONObject argsJson = JSON.parseObject(args);
                        String task = argsJson.getString("task");
                        agentMsg.put("task", task != null ? task : args);
                    } catch (Exception e) {
                        agentMsg.put("task", args);
                    }
                }
                return AgentResponse.json(AgentResponse.TYPE_AGENT_START, agentMsg.toJSONString());
            } else if ("done".equals(status)) {
                // 清除 SubAgentContext
                SubAgentContext.clear();

                JSONObject agentMsg = new JSONObject();
                agentMsg.put("agent", toolName);
                agentMsg.put("status", "done");
                String result = json.getString("result");
                if (result != null) {
                    agentMsg.put("result", result);
                }
                return AgentResponse.json(AgentResponse.TYPE_AGENT_DONE, agentMsg.toJSONString());
            } else if ("error".equals(status)) {
                SubAgentContext.clear();

                JSONObject agentMsg = new JSONObject();
                agentMsg.put("agent", toolName);
                agentMsg.put("status", "error");
                agentMsg.put("error", json.getString("error"));
                return AgentResponse.json(AgentResponse.TYPE_AGENT_ERROR, agentMsg.toJSONString());
            }
        } catch (Exception e) {
            log.warn("解析 agent 工具状态失败: {}", e.getMessage());
        }
        return AgentResponse.agentCall(content);
    }

    private void setupSubAgentContext() {
        if (currentSink == null) return;
        SubAgentContext ctx = new SubAgentContext(
                currentConversationId != null ? currentConversationId : "",
                currentSink,
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

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder chatModel(ChatModel chatModel) {
            this.chatModel = chatModel;
            return this;
        }

        public Builder tools(ToolCallback... tools) {
            this.tools = Arrays.asList(tools);
            return this;
        }

        public Builder tools(List<ToolCallback> tools) {
            this.tools = tools;
            return this;
        }

        public Builder systemPrompt(String systemPrompt) {
            this.systemPrompt = systemPrompt;
            return this;
        }

        public Builder maxRounds(int maxRounds) {
            this.maxRounds = maxRounds;
            return this;
        }

        public Builder maxReflectionRounds(int maxReflectionRounds) {
            this.maxReflectionRounds = maxReflectionRounds;
            return this;
        }

        public Builder advisors(List<Advisor> advisors) {
            this.advisors = advisors;
            return this;
        }

        public Builder advisors(Advisor... advisors) {
            this.advisors = Arrays.asList(advisors);
            return this;
        }

        public Builder chatMemory(ChatMemory chatMemory) {
            this.chatMemory = chatMemory;
            return this;
        }

        public Builder conversationService(ChatConversationService conversationService) {
            this.conversationService = conversationService;
            return this;
        }

        public Builder messageService(ChatMessageService messageService) {
            this.messageService = messageService;
            return this;
        }

        public Builder taskManager(AgentTaskManager taskManager) {
            this.taskManager = taskManager;
            return this;
        }

        public Builder knowledgeRetrievalService(KnowledgeRetrievalService knowledgeRetrievalService) {
            this.knowledgeRetrievalService = knowledgeRetrievalService;
            return this;
        }

        public Builder agentRegistry(AgentRegistry agentRegistry) {
            this.agentRegistry = agentRegistry;
            return this;
        }

        public SimpleOrchestrator build() {
            if (chatModel == null) throw new IllegalArgumentException("chatModel 不能为空！");
            if (agentRegistry == null) throw new IllegalArgumentException("agentRegistry 不能为空！");
            return new SimpleOrchestrator(this);
        }
    }
}
