package com.roydon.dear.controller;

import com.alibaba.dashscope.aigc.multimodalconversation.AudioParameters;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversation;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationParam;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationResult;
import com.alibaba.dashscope.exception.ApiException;
import com.alibaba.dashscope.exception.NoApiKeyException;
import com.alibaba.dashscope.exception.UploadFileException;
import com.alibaba.dashscope.utils.Constants;
import com.alibaba.dashscope.utils.JsonUtils;
import com.roydon.dear.agent.DearAgent;
import com.roydon.dear.domain.entity.AiSession;
import com.roydon.dear.manager.AgentTaskManager;
import com.roydon.dear.service.AiSessionService;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.reactivex.Flowable;
import io.swagger.v3.oas.annotations.Operation;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 智能体控制器
 * 提供网页搜索、文件问答和PPT生成的流式接口
 */
@Slf4j
@RestController
@RequestMapping("/agent")
public class AgentController implements InitializingBean {

    @Autowired
    private ChatModel chatModel;

    @Autowired
    private AiSessionService sessionService;

    @Autowired
    private AgentTaskManager taskManager;

    /**
     * Tavily 搜索引擎 API Key
     */
    @Value("${tavily.api-key}")
    private String tavilyApiKey;

    /**
     * Tavily MCP URL
     */
    @Value("${tavily.mcp-url}")
    private String tavilyMcpUrl;

    /**
     * 网页搜索工具回调
     */
    private ToolCallback[] webSearchToolCallbacks;

    @GetMapping(value = "/chat/stream", produces = "text/event-stream;charset=UTF-8")
    @Operation(summary = "智能问答", description = "接收用户查询并返回流式响应，使用联网搜索获取信息")
    public Flux<String> webSearchStream(@RequestParam(required = true) String query,
                                        @RequestParam(required = true) String conversationId,
                                        @RequestParam(required = false) Boolean think,
                                        @RequestParam(required = false) Boolean webSearch) {
        // todo conversationId如果为空则创建session对话，返回给前端conversationId

        boolean thinkEnabled = Boolean.TRUE.equals(think);
        log.info("收到请求: query={}, conversationId={}, think={}", query, conversationId, thinkEnabled);

        if (query == null || query.trim().isEmpty()) {
            log.warn("参数为空或无效");
            return Flux.error(new IllegalArgumentException("参数不能为空"));
        }

        try {
            // 使用持久化记忆加载历史记录
            DearAgent dearAgent = initDearAgent(conversationId);
            return dearAgent.stream(conversationId, query, thinkEnabled);
        } catch (Exception e) {
            log.error("处理网页搜索请求时发生错误: ", e);
            return Flux.error(e);
        }
    }

//    @GetMapping(value = "/deep/stream", produces = "text/event-stream;charset=UTF-8")
//    @Operation(summary = "深度研究", description = "接收用户查询并返回流式响应，使用计划-执行模式进行深度研究")
//    public Flux<String> deepStream(@RequestParam(required = true) String query,
//                                    @RequestParam(required = true) String conversationId) {
//        log.info("收到深度研究请求: query={}, conversationId={}", query, conversationId);
//
//        if (query == null || query.trim().isEmpty()) {
//            log.warn("查询参数为空或无效");
//            return Flux.error(new IllegalArgumentException("查询参数不能为空"));
//        }
//
//        try {
//            PlanExecuteAgent planExecuteAgent = initPlanExecuteAgent();
//            // 使用持久化记忆加载历史记录
//            ChatMemory persistentMemory = planExecuteAgent.createPersistentChatMemory(conversationId, 30);
//            planExecuteAgent.setChatMemory(persistentMemory);
//            return planExecuteAgent.stream(conversationId, query);
//        } catch (Exception e) {
//            log.error("处理深度研究请求时发生错误: ", e);
//            return Flux.error(e);
//        }
//    }

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

    @Override
    public void afterPropertiesSet() throws Exception {
        log.info("开始初始化工具toolcallback");

        // 初始化网页搜索工具回调
        initWebSearchToolCallbacks();

        log.info("工具toolcallback初始化完成");
    }

    /**
     * 初始化网页搜索工具回调
     */
    private void initWebSearchToolCallbacks() throws Exception {
        log.info("初始化网页搜索工具回调...");

        // tavily 搜索引擎
        String authorizationHeader = "Bearer " + tavilyApiKey;

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .header("Authorization", authorizationHeader);

        HttpClientStreamableHttpTransport tavTransport = HttpClientStreamableHttpTransport.builder(tavilyMcpUrl)
                .requestBuilder(requestBuilder).build();
        McpSyncClient tavilyMcp = McpClient.sync(tavTransport)
                .requestTimeout(Duration.ofSeconds(300))
                .build();
        tavilyMcp.initialize();

        List<McpSyncClient> mcpClients = List.of(tavilyMcp);
        SyncMcpToolCallbackProvider provider = SyncMcpToolCallbackProvider.builder().mcpClients(mcpClients).build();

        webSearchToolCallbacks = provider.getToolCallbacks();
        log.info("网页搜索工具回调初始化完成，工具数量: {}", webSearchToolCallbacks.length);
    }

    /**
     * 初始化 Agent
     */
    private DearAgent initDearAgent(String conversationId) {
        log.info("初始化 Agent...");
        DearAgent dearReact = DearAgent.builder()
                .name("dear react")
                .chatModel(chatModel)
                .tools(webSearchToolCallbacks)
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

    /**
     * 从数据库加载历史记录创建持久化ChatMemory
     */
    private ChatMemory createPersistentChatMemory(String sessionId, int maxMessages) {
        // 查询数据库中的对话历史
        List<AiSession> history = sessionService.findRecentBySessionId(sessionId, maxMessages);
        ChatMemory chatMemory = MessageWindowChatMemory.builder().maxMessages(maxMessages).build();

        // 按时间顺序加载历史记录
        if (history != null && !history.isEmpty()) {
            for (int i = history.size() - 1; i >= 0; i--) {
                AiSession record = history.get(i);
                if (record.getQuestion() != null) {
                    chatMemory.add(sessionId, new UserMessage(record.getQuestion()));
                }
                if (record.getAnswer() != null) {
                    chatMemory.add(sessionId, new AssistantMessage(record.getAnswer()));
                }
            }
        }
        return chatMemory;
    }

    /**
     * 初始化 PlanExecute Agent
     */
//    private PlanExecuteAgent initPlanExecuteAgent() {
//        log.info("初始化 PlanExecute Agent...");
//
//        return PlanExecuteAgent.builder()
//                .chatModel(chatModel)
//                .tools(webSearchToolCallbacks)
//                .sessionService(sessionService)
//                .taskManager(taskManager)
//                .maxRounds(3)
//                .build();
//    }

    // 如需使用指令控制功能，请将MODEL替换为qwen3-tts-instruct-flash
//    private static final String MODEL = "qwen3-tts-flash";
    private static final String MODEL = "qwen3-tts-vd-2026-01-26";

    public static void streamCall() throws ApiException, NoApiKeyException, UploadFileException {
        MultiModalConversation conv = new MultiModalConversation();
        MultiModalConversationParam param = MultiModalConversationParam.builder()
                .model(MODEL)
                .apiKey("sk-cec420dff8b04f5f8aedf0d5e2c98a50")
                .text("那我来给大家推荐一款T恤，这款呢真的是超级好看，这个颜色呢很显气质，而且呢也是搭配的绝佳单品，大家可以闭眼入，真的是非常好看，对身材的包容性也很好，不管啥身材的宝宝呢，穿上去都是很好看的。推荐宝宝们下单哦。")
                .voice(AudioParameters.Voice.CHERRY)
                .languageType("Chinese")
                // 如需使用指令控制功能，请取消下方注释，并将model替换为qwen3-tts-instruct-flash
                // .parameter("instructions","语速较快，带有明显的上扬语调，适合介绍时尚产品。")
                // .parameter("optimize_instructions",true)
                .build();
        Flowable<MultiModalConversationResult> result = conv.streamCall(param);
        result.blockingForEach(r -> {
            System.out.println(JsonUtils.toJson(r));
        });
    }

    public static void main(String[] args) {
        // 以下为北京地域url，若使用新加坡地域的模型，需将url替换为：https://dashscope-intl.aliyuncs.com/api/v1
        Constants.baseHttpApiUrl = "https://dashscope.aliyuncs.com/api/v1";
        try {
            streamCall();
        } catch (ApiException | NoApiKeyException | UploadFileException e) {
            System.out.println(e.getMessage());
        }
        System.exit(0);
    }
}
