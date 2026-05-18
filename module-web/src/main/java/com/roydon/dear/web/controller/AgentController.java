package com.roydon.dear.web.controller;

import com.alibaba.cloud.ai.graph.agent.hook.skills.ReadSkillTool;
import com.alibaba.cloud.ai.graph.skills.SpringAiSkillAdvisor;
import com.alibaba.cloud.ai.graph.skills.registry.SkillRegistry;
import com.alibaba.cloud.ai.graph.skills.registry.filesystem.FileSystemSkillRegistry;
import com.roydon.dear.agent.DearAgent;
import com.roydon.dear.common.AgentResponse;
import com.roydon.dear.common.manager.AgentTaskManager;
import com.roydon.dear.common.prompts.ReactAgentPrompts;
import com.roydon.dear.model.registry.ModelRegistry;
import com.roydon.dear.model.tts.AgentVoiceStreamService;
import com.roydon.dear.prompt.entity.AiPrompt;
import com.roydon.dear.prompt.service.AiPromptService;
import com.roydon.dear.session.entity.ChatConversation;
import com.roydon.dear.session.service.ChatConversationService;
import com.roydon.dear.session.service.ChatMessageService;
import com.roydon.dear.tool.McpToolManager;
import io.swagger.v3.oas.annotations.Operation;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

@Slf4j
@RestController
@RequestMapping("/agent")
public class AgentController {

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
    private AgentVoiceStreamService agentVoiceStreamService;

    @Autowired
    private McpToolManager mcpToolManager;

    @GetMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "智能问答", description = "接收用户查询并返回流式响应，使用联网搜索获取信息")
    public Flux<String> webSearchStream(@RequestParam(required = true) String query,
                                        @RequestParam(required = true) String conversationId,
                                        @RequestParam(required = false) Boolean think,
                                        @RequestParam(required = false) Boolean webSearch,
                                        @RequestParam(required = false, defaultValue = "false") Boolean voiceOutput,
                                        @RequestParam(required = false) String voice) {
        boolean thinkEnabled = Boolean.TRUE.equals(think);
        boolean webSearchEnabled = Boolean.TRUE.equals(webSearch);
        boolean voiceEnabled = Boolean.TRUE.equals(voiceOutput);
        log.info("收到请求: query={}, conversationId={}, think={}, webSearch={}, voiceOutput={}, voice={}",
                query, conversationId, thinkEnabled, webSearchEnabled, voiceEnabled, voice);

        if (query == null || query.trim().isEmpty()) {
            log.warn("参数为空或无效");
            return Flux.error(new IllegalArgumentException("参数不能为空"));
        }

        try {
            DearAgent dearAgent = initDearAgent(conversationId, webSearchEnabled);
            Flux<String> agentStream = dearAgent.stream(conversationId, query, thinkEnabled);
            if (voiceEnabled) {
                return agentVoiceStreamService.withVoice(agentStream, voice);
            }
            return agentStream;
        } catch (IllegalStateException e) {
            log.warn("模型配置异常: {}", e.getMessage());
            return Flux.just(
                    AgentResponse.error("模型未配置：" + e.getMessage()),
                    AgentResponse.done("error"));
        } catch (Exception e) {
            log.error("处理请求时发生错误: ", e);
            return Flux.just(
                    AgentResponse.error("服务异常：" + e.getMessage()),
                    AgentResponse.done("error"));
        }
    }

    @GetMapping("/stop")
    @Operation(summary = "停止Agent执行", description = "停止指定会话的Agent执行，中断底层调用")
    public Map<String, Object> stopAgent(@RequestParam String conversationId) {
        log.info("收到停止请求: conversationId={}", conversationId);
        boolean success = taskManager.stopTask(conversationId);
        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        if (success) {
            result.put("success", true);
            result.put("message", "已停止执行");
        } else {
            result.put("success", false);
            result.put("message", "没有找到正在执行的任务或已停止");
        }
        return result;
    }

    private DearAgent initDearAgent(String conversationId, boolean webSearchEnabled) {
        log.debug("开始初始化DearAgent: conversationId={}, webSearchEnabled={}", conversationId, webSearchEnabled);
        String systemPrompt;
        ToolCallback[] tools;
        // 填充系统提示词
        if (StringUtils.isNotBlank(conversationId)) {
            ChatConversation chatConversation = conversationService.getBySessionId(conversationId);
            Long promptId = chatConversation.getPromptId();
            if (Objects.nonNull(promptId)) {
                AiPrompt aiPrompt = aiPromptService.getById(promptId);
                systemPrompt = aiPrompt.getPrompt();
            } else {
                systemPrompt = ReactAgentPrompts.cozeSysPrompt();
            }
        } else {
            systemPrompt = ReactAgentPrompts.cozeSysPrompt();
        }

        // 给系统提示词拼接时间
        systemPrompt = systemPrompt + ReactAgentPrompts.getJoinSysPrompt();
        tools = mcpToolManager.getAllTools();

        ChatModel chatModel = modelRegistry.getDefaultChatModel("chat");

        SkillRegistry skillRegistry = FileSystemSkillRegistry.builder()
                .userSkillsDirectory(System.getProperty("user.home") + "/.dear-agent/.skills")
                .build();

        /*
          3. 创建 SpringAiSkillAdvisor，把 SkillRegistry 注入进去
             Advisor 会在每次对话的 before() 阶段将 Skill 列表追加到 System Prompt
         */
        SpringAiSkillAdvisor skillAdvisor = SpringAiSkillAdvisor.builder()
                .skillRegistry(skillRegistry)
                .build();

        // todo 根据不同类型切换不同agent

        DearAgent dearReact = DearAgent.builder()
                .name("dear react")
                .chatModel(chatModel)
                .tools(tools) // function call / mcp / read_skill
                .advisors(skillAdvisor) // skill advisors
                .systemPrompt(systemPrompt)
                .conversationService(conversationService)
                .messageService(messageService)
                .taskManager(taskManager)
                .maxRounds(10)
                .build();
        log.debug("初始化DearReact完成");
        if (StringUtils.isNotBlank(conversationId)) {
            ChatMemory chatMemory = dearReact.createPersistentChatMemory(conversationId, 30);
            dearReact.setChatMemory(chatMemory);
        }
        return dearReact;
    }


}
