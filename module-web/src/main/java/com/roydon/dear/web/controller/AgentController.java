package com.roydon.dear.web.controller;

import com.roydon.dear.agent.DearAgent;
import com.roydon.dear.common.AgentResponse;
import com.roydon.dear.common.manager.AgentTaskManager;
import com.roydon.dear.common.prompts.ReactAgentPrompts;
import com.roydon.dear.model.registry.ModelRegistry;
import com.roydon.dear.model.tts.AgentVoiceStreamService;
import com.roydon.dear.session.entity.AiSession;
import com.roydon.dear.session.service.AiSessionService;
import com.roydon.dear.tool.McpToolManager;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/agent")
public class AgentController {

    @Autowired
    private ModelRegistry modelRegistry;

    @Autowired
    private AiSessionService sessionService;

    @Autowired
    private AgentTaskManager taskManager;

    @Autowired
    private AgentVoiceStreamService agentVoiceStreamService;

    @Autowired
    private McpToolManager mcpToolManager;

    @GetMapping(value = "/chat/stream", produces = "text/event-stream;charset=UTF-8")
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
        String prompt;
        ToolCallback[] tools;
//        if (webSearchEnabled) {
        prompt = ReactAgentPrompts.cozeSysPrompt();
        tools = mcpToolManager.getAllTools();
//            log.info("初始化 Agent（联网模式），工具数量: {}", tools.length);
//        } else {
//            prompt = ReactAgentPrompts.getFileOperationPrompt();
//            tools = mcpToolManager.getFileTools();
//            log.info("初始化 Agent（离线文件操作模式），工具数量: {}", tools.length);
//        }

        // 初始化 ChatModel

        ChatModel chatModel = modelRegistry.getDefaultChatModel("chat");

        DearAgent dearReact = DearAgent.builder()
                .name("dear react")
                .chatModel(chatModel)
                .tools(tools)
                .systemPrompt(prompt)
                .sessionService(sessionService)
                .taskManager(taskManager)
                .maxRounds(5)
                .build();

        if (StringUtils.isNotBlank(conversationId)) {
            ChatMemory chatMemory = createPersistentChatMemory(conversationId, 30);
            dearReact.setChatMemory(chatMemory);
        }
        return dearReact;
    }

    private ChatMemory createPersistentChatMemory(String sessionId, int maxMessages) {
        List<AiSession> history = sessionService.findRecentBySessionId(sessionId, maxMessages);
        ChatMemory chatMemory = MessageWindowChatMemory.builder().maxMessages(maxMessages).build();
        if (history != null && !history.isEmpty()) {
            for (int i = history.size() - 1; i >= 0; i--) {
                AiSession record = history.get(i);
                if (record.getQuestion() != null) chatMemory.add(sessionId, new UserMessage(record.getQuestion()));
                if (record.getAnswer() != null) chatMemory.add(sessionId, new AssistantMessage(record.getAnswer()));
            }
        }
        return chatMemory;
    }
}
