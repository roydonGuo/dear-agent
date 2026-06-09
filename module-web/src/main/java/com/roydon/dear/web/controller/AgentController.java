package com.roydon.dear.web.controller;

import com.alibaba.cloud.ai.graph.skills.SpringAiSkillAdvisor;
import com.alibaba.cloud.ai.graph.skills.registry.SkillRegistry;
import com.alibaba.cloud.ai.graph.skills.registry.filesystem.FileSystemSkillRegistry;
import com.roydon.dear.agent.DearAgent;
import com.roydon.dear.common.AgentResponse;
import com.roydon.dear.common.manager.AgentTaskManager;
import com.roydon.dear.common.prompts.ReactAgentPrompts;
import com.roydon.dear.event.AgentEventBus;
import com.roydon.dear.event.DefaultAgentEventBus;
import com.roydon.dear.event.SessionEventPersister;
import com.roydon.dear.event.SseEventEmitter;
import com.roydon.dear.knowledge.rag.retriever.KnowledgeRetrievalService;
import com.roydon.dear.model.registry.ModelRegistry;
import com.roydon.dear.model.tts.AgentVoiceStreamService;
import com.roydon.dear.prompt.entity.AiPrompt;
import com.roydon.dear.prompt.service.AiPromptService;
import com.roydon.dear.session.entity.AiChatFile;
import com.roydon.dear.session.entity.ChatConversation;
import com.roydon.dear.session.enums.ModelCategoryEnum;
import com.roydon.dear.session.service.ChatConversationService;
import com.roydon.dear.session.service.ChatMessageService;
import com.roydon.dear.session.service.IAiChatFileService;
import com.roydon.dear.tool.McpToolManager;
import com.roydon.dear.web.common.BusinessMetrics;
import io.micrometer.core.annotation.Timed;
import io.swagger.v3.oas.annotations.Operation;
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

    @Autowired
    private IAiChatFileService aiChatFileService;

    @Autowired
    private BusinessMetrics businessMetrics;

    @Autowired
    private KnowledgeRetrievalService knowledgeRetrievalService;

    /**
     * 智能问答流式接口
     * <p>
     * 接收用户查询请求，根据配置参数执行智能问答，并返回Server-Sent Events(SSE)格式的流式响应。
     * 支持深度思考、联网搜索、语音输出等功能。
     * </p>
     *
     * @param query            用户查询内容，不能为空
     * @param conversationId   会话ID，用于标识和追踪对话上下文
     * @param think            是否启用深度思考模式，null或false时不启用
     * @param webSearch        是否启用联网搜索，null或false时不启用
     * @param voiceOutput      是否启用语音输出，默认为false
     * @param voice            语音类型或音色标识，可选参数
     * @param fileIds          关联的文件ID列表，可选参数
     * @param useKnowledgeBase 自由决策检索知识库，优先级大于knowledgeBaseIds，可选参数
     * @param knowledgeBaseIds 关联的文件库ID列表，可选参数
     *                         </p>
     * @return Flux<String>  返回响应式流式数据，包含AI回复内容或错误信息
     */
    @Timed(value = "agent.chat.stream", description = "Agent chat stream endpoint")
    @GetMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "智能问答", description = "接收用户查询并返回流式响应，使用联网搜索获取信息")
    public Flux<String> chatStream(@RequestParam String query,
                                   @RequestParam String conversationId,
                                   @RequestParam(required = false) Boolean think,
                                   @RequestParam(required = false) String thinkDepth,
                                   @RequestParam(required = false) Boolean webSearch,
                                   @RequestParam(required = false, defaultValue = "false") Boolean voiceOutput,
                                   @RequestParam(required = false) String voice,
                                   @RequestParam(required = false) String fileIds,
                                   @RequestParam(required = false) Boolean useKnowledgeBase,
                                   @RequestParam(required = false) String knowledgeBaseIds) {
        boolean thinkEnabled = Boolean.TRUE.equals(think);
        boolean webSearchEnabled = Boolean.TRUE.equals(webSearch);
        boolean voiceEnabled = Boolean.TRUE.equals(voiceOutput);
        log.info("收到请求: query={}, conversationId={}, think={}, webSearch={}, voiceOutput={}, voice={}",
                query, conversationId, thinkEnabled, webSearchEnabled, voiceEnabled, voice);

        businessMetrics.recordChatRequest();

        if (query == null || query.trim().isEmpty()) {
            log.warn("参数为空或无效");
            return Flux.error(new IllegalArgumentException("参数不能为空"));
        }

        try {
            // 创建本次请求的事件总线和 SSE 发射器
            AgentEventBus eventBus = new DefaultAgentEventBus();
            SseEventEmitter sseEmitter = new SseEventEmitter(eventBus);

            DearAgent dearAgent = initDearAgent(conversationId, webSearchEnabled, fileIds, eventBus);
            Flux<String> agentStream = dearAgent.stream(conversationId, query, thinkEnabled, fileIds,
                    useKnowledgeBase, knowledgeBaseIds, sseEmitter);

            // 订阅 agent 内部 Flux 以触发 doFinally（保存 + 清理），SSE 输出由 sseEmitter 提供
            agentStream.subscribe(
                    null,
                    error -> log.error("Agent stream error: {}", error.getMessage()),
                    () -> log.debug("Agent stream completed")
            );

            // 使用事件总线的 SSE 流
            Flux<String> sseFlux = sseEmitter.toSseFlux();

            if (voiceEnabled) {
                return agentVoiceStreamService.withVoice(sseFlux, voice);
            }
            return sseFlux;
        } catch (IllegalStateException e) {
            log.warn("模型配置异常: {}", e.getMessage());
            businessMetrics.recordChatError();
            return Flux.just(
                    AgentResponse.error("模型未配置：" + e.getMessage()),
                    AgentResponse.done("error"));
        } catch (Exception e) {
            log.error("处理请求时发生错误: ", e);
            businessMetrics.recordChatError();
            return Flux.just(
                    AgentResponse.error("服务异常：" + e.getMessage()),
                    AgentResponse.done("error"));
        }
    }

    @Timed(value = "agent.stop", description = "Agent stop endpoint")
    @GetMapping("/stop")
    @Operation(summary = "停止Agent执行", description = "停止指定会话的Agent执行，中断底层调用")
    public Map<String, Object> stopAgent(@RequestParam String conversationId) {
        log.info("收到停止请求: conversationId={}", conversationId);
        businessMetrics.recordStopRequest();
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

    private DearAgent initDearAgent(String conversationId, boolean webSearchEnabled, String fileIds) {
        return initDearAgent(conversationId, webSearchEnabled, fileIds, null);
    }

    private DearAgent initDearAgent(String conversationId, boolean webSearchEnabled, String fileIds,
                                    AgentEventBus eventBus) {
        log.debug("开始初始化DearAgent: conversationId={}, webSearchEnabled={}, useEventBus={}",
                conversationId, webSearchEnabled, eventBus != null);
        String systemPrompt;
        ToolCallback[] tools;

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

        if (StringUtils.isNotBlank(fileIds)) {
            List<AiChatFile> chatFileList = aiChatFileService.getListByIds(fileIds);
            AtomicReference<String> prompt = new AtomicReference<>("""
                    # 用户携带了以下文件描述信息：
                    """);
            chatFileList.forEach(chatFile -> {
                prompt.set(prompt + """
                        ## 文件名：%s
                        ## 文件描述：%s
                        ## 文件大小：%s
                        ## 文件类型：%s
                        ## 文件创建时间：%s
                        """.formatted(chatFile.getFileName(), chatFile.getExtractedText(), chatFile.getFileSize(),
                        chatFile.getFileType(), chatFile.getCreatedTime()));
            });
            systemPrompt = systemPrompt + prompt.get();
        }

        systemPrompt = systemPrompt + ReactAgentPrompts.getJoinSysPrompt();
        tools = mcpToolManager.getAllTools();

        ChatModel chatModel = modelRegistry.getDefaultChatModel(ModelCategoryEnum.CHAT.getCode());

        SkillRegistry skillRegistry = FileSystemSkillRegistry.builder()
                .userSkillsDirectory(System.getProperty("user.home") + "/.dear-agent/.skills")
                .build();

        SpringAiSkillAdvisor skillAdvisor = SpringAiSkillAdvisor.builder()
                .skillRegistry(skillRegistry)
                .build();

        DearAgent dearReact = DearAgent.builder()
                .name("dear react")
                .chatModel(chatModel)
                .tools(tools)
                .advisors(skillAdvisor)
                .systemPrompt(systemPrompt)
                .conversationService(conversationService)
                .messageService(messageService)
                .taskManager(taskManager)
                .knowledgeRetrievalService(knowledgeRetrievalService)
                .eventBus(eventBus)
                .maxRounds(50)
                .build();
        log.debug("初始化DearReact完成");

        if (StringUtils.isNotBlank(conversationId)) {
            ChatMemory chatMemory = dearReact.createPersistentChatMemory(conversationId, 30);
            dearReact.setChatMemory(chatMemory);
        }
        return dearReact;
    }
}
