package com.roydon.dear.web.controller;

import com.alibaba.cloud.ai.graph.skills.SpringAiSkillAdvisor;
import com.alibaba.cloud.ai.graph.skills.registry.SkillRegistry;
import com.alibaba.cloud.ai.graph.skills.registry.filesystem.FileSystemSkillRegistry;
import com.roydon.dear.agent.orchestrator.PlanExecuteAgent;
import com.roydon.dear.agent.orchestrator.SimpleOrchestrator;
import com.roydon.dear.agent.registry.AgentRegistry;
import com.roydon.dear.common.AgentResponse;
import com.roydon.dear.common.manager.AgentTaskManager;
import com.roydon.dear.common.prompts.ReactAgentPrompts;
import com.roydon.dear.knowledge.rag.retriever.KnowledgeRetrievalService;
import com.roydon.dear.model.registry.ModelRegistry;
import com.roydon.dear.prompt.entity.AiPrompt;
import com.roydon.dear.prompt.service.AiPromptService;
import com.roydon.dear.session.entity.AiChatFile;
import com.roydon.dear.session.entity.ChatConversation;
import com.roydon.dear.session.enums.ModelCategoryEnum;
import com.roydon.dear.session.service.ChatConversationService;
import com.roydon.dear.session.service.ChatMessageService;
import com.roydon.dear.session.service.IAiChatFileService;
import com.roydon.dear.tool.McpToolManager;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@RestController
@RequestMapping("/multi-agent")
public class MultiAgentController {

    @Autowired
    private ModelRegistry modelRegistry;

    @Autowired
    private ChatConversationService conversationService;

    @Autowired
    private ChatMessageService messageService;

    @Autowired
    private AiPromptService aiPromptService;

    @Autowired
    private AgentTaskManager taskManager;

    @Autowired
    private McpToolManager mcpToolManager;

    @Autowired
    private IAiChatFileService aiChatFileService;

    @Autowired
    private KnowledgeRetrievalService knowledgeRetrievalService;

    @Autowired
    private AgentRegistry agentRegistry;

    /**
     * 多 Agent 协同流式接口
     *
     * @param query          用户查询内容
     * @param conversationId 会话ID
     * @param mode           协作模式：auto(LLM自动调度) | research(深度研究PlanExecute)
     * @param think          是否启用深度思考
     * @param fileIds        关联文件ID列表
     */
    @GetMapping(value = "/collaborate/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> collaborateStream(@RequestParam String query,
                                          @RequestParam String conversationId,
                                          @RequestParam(defaultValue = "auto") String mode,
                                          @RequestParam(required = false) Boolean think,
                                          @RequestParam(required = false) String fileIds) {
        log.info("多Agent协同请求: query={}, conversationId={}, mode={}", query, conversationId, mode);

        try {
            if ("plan_execute".equals(mode)) {
                PlanExecuteAgent agent = buildPlanExecuteAgent();
                return agent.execute(conversationId, query);
            }
            // 默认 mode=auto: SimpleOrchestrator
            SimpleOrchestrator orchestrator = buildOrchestrator(conversationId, fileIds);
            return orchestrator.stream(conversationId, query, Boolean.TRUE.equals(think), fileIds, null, null);
        } catch (Exception e) {
            log.error("多Agent协同异常", e);
            return Flux.just(
                    AgentResponse.error("多Agent协同异常：" + e.getMessage()),
                    AgentResponse.done("error"));
        }
    }

    /**
     * 停止多 Agent 协同执行
     */
    @GetMapping("/stop")
    public Map<String, Object> stopCollaborate(@RequestParam String conversationId) {
        log.info("停止多Agent协同: conversationId={}", conversationId);
        boolean success = taskManager.stopTask(conversationId);
        return Map.of("code", 200, "success", success,
                "message", success ? "已停止执行" : "没有找到正在执行的任务");
    }

    // ===== 编排器构建（与 AgentController.initDearAgent 平行，互不影响） =====

    private SimpleOrchestrator buildOrchestrator(String conversationId, String fileIds) {
        String systemPrompt = loadSystemPrompt(conversationId, fileIds);
        ToolCallback[] tools = mcpToolManager.getAllTools();
        ChatModel chatModel = modelRegistry.getDefaultChatModel(ModelCategoryEnum.CHAT.getCode());

        SkillRegistry skillRegistry = FileSystemSkillRegistry.builder()
                .userSkillsDirectory(System.getProperty("user.home") + "/.dear-agent/.skills")
                .build();
        SpringAiSkillAdvisor skillAdvisor = SpringAiSkillAdvisor.builder()
                .skillRegistry(skillRegistry)
                .build();

        SimpleOrchestrator orchestrator = SimpleOrchestrator.orchestratorBuilder()
                .name("multi-agent-orchestrator")
                .chatModel(chatModel)
                .tools(tools)
                .advisors(skillAdvisor)
                .systemPrompt(enhanceSystemPromptForMultiAgent(systemPrompt))
                .conversationService(conversationService)
                .messageService(messageService)
                .taskManager(taskManager)
                .knowledgeRetrievalService(knowledgeRetrievalService)
                .maxRounds(50)
                .agentRegistry(agentRegistry)
                .build();

        if (StringUtils.isNotBlank(conversationId)) {
            ChatMemory chatMemory = orchestrator.createPersistentChatMemory(conversationId, 30);
            orchestrator.setChatMemory(chatMemory);
        }
        return orchestrator;
    }

    private PlanExecuteAgent buildPlanExecuteAgent() {
        ToolCallback[] tools = mcpToolManager.getAllTools();
        ChatModel chatModel = modelRegistry.getDefaultChatModel(ModelCategoryEnum.CHAT.getCode());

        List<ToolCallback> allTools = new ArrayList<>(Arrays.asList(tools));
        allTools.addAll(agentRegistry.getAgentTools());

        return PlanExecuteAgent.builder()
                .name("plan-execute-agent")
                .chatModel(chatModel)
                .tools(allTools)
                .agentRegistry(agentRegistry)
                .maxPlanSteps(8)
                .conversationService(conversationService)
                .messageService(messageService)
                .taskManager(taskManager)
                .build();
    }

    private String loadSystemPrompt(String conversationId, String fileIds) {
        if (StringUtils.isNotBlank(conversationId)) {
            ChatConversation chatConversation = conversationService.getBySessionId(conversationId);
            Long promptId = chatConversation.getPromptId();
            if (Objects.nonNull(promptId)) {
                AiPrompt aiPrompt = aiPromptService.getById(promptId);
                return aiPrompt.getPrompt();
            }
        }
        return ReactAgentPrompts.cozeSysPrompt();
    }

    /**
     * 在多 Agent 场景下增强系统提示词，告知 LLM 可调度的 Agent 信息。
     */
    private String enhanceSystemPromptForMultiAgent(String basePrompt) {
        StringBuilder sb = new StringBuilder(basePrompt);
        sb.append("\n\n## 可调度的专业 Agent\n");
        sb.append("你可以调用以下专业 Agent 来协作完成任务：\n");
        for (String name : agentRegistry.getAgentNames()) {
            var meta = agentRegistry.getMetadata(name);
            sb.append("- **").append(name).append("**: ").append(meta.description()).append("\n");
        }
        sb.append("\n根据任务复杂度自主决定是否调度 Agent。简单任务可直接回答。\n");
        return sb.toString();
    }
}
