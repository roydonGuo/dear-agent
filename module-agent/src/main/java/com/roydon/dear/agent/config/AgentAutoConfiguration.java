package com.roydon.dear.agent.config;

import com.roydon.dear.agent.WebSearchReactAgent;
import com.roydon.dear.agent.orchestrator.PlanExecuteAgent;
import com.roydon.dear.agent.registry.AgentRegistry;
import com.roydon.dear.common.manager.AgentTaskManager;
import com.roydon.dear.model.registry.ModelRegistry;
import com.roydon.dear.session.service.ChatConversationService;
import com.roydon.dear.session.service.ChatMessageService;
import com.roydon.dear.tool.registry.McpRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Configuration
public class AgentAutoConfiguration {

    @Autowired
    private ModelRegistry modelRegistry;

    @Autowired
    private ChatConversationService conversationService;

    @Autowired
    private ChatMessageService messageService;

    @Autowired
    private AgentTaskManager taskManager;

    @Autowired
    private McpRegistry mcpRegistry;

    @Bean
    public AgentRegistry agentRegistry() {
        return new AgentRegistry();
    }

    /**
     * 注册内置 Agent 到注册中心。
     * 新增 Agent 只需在此处添加一行 register 调用。
     */
    @Bean
    @DependsOn("agentRegistry")
    public Object registerBuiltinAgents(AgentRegistry registry) {
        try {
            ChatModel chatModel = modelRegistry.getDefaultChatModel("chat");
            List<ToolCallback> mcpTools = mcpRegistry.getAllToolCallbacks();

            // 1. 注册 WebSearchReactAgent
            WebSearchReactAgent searchAgent = WebSearchReactAgent.builder()
                    .name("web-search-agent")
                    .chatModel(chatModel)
                    .tools(mcpTools)
                    .conversationService(conversationService)
                    .messageService(messageService)
                    .taskManager(taskManager)
                    .maxRounds(10)
                    .build();
            registry.register(searchAgent, searchAgent);
            log.info("已注册内置 Agent: {}", searchAgent.agentName());

            // 2. 注册 PlanExecuteAgent（可被编排器调度的计划执行 Agent）
            List<ToolCallback> planTools = new ArrayList<>(mcpTools);
            planTools.addAll(registry.getAgentTools()); // 包含 web_search_agent
            PlanExecuteAgent planAgent = PlanExecuteAgent.builder()
                    .name("plan_execute_agent")
                    .chatModel(chatModel)
                    .tools(planTools)
                    .agentRegistry(registry)
                    .maxPlanSteps(8)
                    .conversationService(conversationService)
                    .messageService(messageService)
                    .taskManager(taskManager)
                    .build();
            registry.register(planAgent, new PlanExecuteAgentMetadata());
            log.info("已注册内置 Agent: plan_execute_agent");
        } catch (Exception e) {
            log.warn("注册内置 Agent 失败（可能缺少模型配置）: {}", e.getMessage());
        }

        return new Object();
    }

    /**
     * PlanExecuteAgent 的元信息（内联实现，避免污染 Agent 类本身）
     */
    private static class PlanExecuteAgentMetadata implements com.roydon.dear.agent.registry.AgentMetadata {
        @Override public String agentName() { return "plan_execute_agent"; }
        @Override
        public String description() {
            return "计划执行专家，擅长将复杂任务拆解为分步计划并逐一执行。输入格式：直接传入你需要完成的复杂任务描述。";
        }
        @Override public String role() { return "plan"; }
        @Override
        public String callSync(String input) {
            // PlanExecuteAgent 的同步调用：走 execute 收集文本
            // 此处不实现，由 AgentToolAdapter 在编排器上下文中自动走流式路径
            return "[plan_execute_agent 需要在中执行]";
        }
    }
}
