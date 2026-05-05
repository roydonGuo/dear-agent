package com.roydon.dear.agent;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.roydon.dear.common.domain.agent.AgentState;
import com.roydon.dear.common.domain.agent.RoundMode;
import com.roydon.dear.common.domain.agent.RoundState;
import com.roydon.dear.common.domain.agent.SearchResult;
import com.roydon.dear.common.manager.AgentTaskManager;
import com.roydon.dear.common.prompts.ReactAgentPrompts;
import com.roydon.dear.session.entity.AiSession;
import com.roydon.dear.session.req.SaveQuestionRequest;
import com.roydon.dear.session.req.UpdateAnswerRequest;
import com.roydon.dear.session.service.AiSessionService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.util.CollectionUtils;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
public class DearAgent extends BaseAgent {

    private ChatClient chatClient;
    private final List<ToolCallback> tools;
    private final String systemPrompt;
    private int maxRounds;
    private final List<Advisor> advisors;
    private final int maxReflectionRounds;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public DearAgent(String name, ChatModel chatModel, List<ToolCallback> tools, String systemPrompt, int maxRounds,
                     ChatMemory chatMemory, List<Advisor> advisors, int maxReflectionRounds,
                     AiSessionService sessionService, AgentTaskManager taskManager) {
        super(name, chatModel, "websearch");
        this.tools = tools;
        this.systemPrompt = systemPrompt;
        this.maxRounds = maxRounds;
        this.advisors = advisors;
        this.maxReflectionRounds = maxReflectionRounds;
        this.chatMemory = chatMemory;
        this.sessionService = sessionService;
        this.taskManager = taskManager;
        this.usedTools = new HashSet<>();
        initChatClient();
        if (this.chatClient == null) {
            throw new IllegalStateException("ChatClient 初始化失败！");
        }
    }

    private void initChatClient() {
        try {
            ToolCallingChatOptions toolOptions = ToolCallingChatOptions.builder()
                    .toolCallbacks(tools)
                    .internalToolExecutionEnabled(false)
                    .build();
            ChatClient.Builder builder = ChatClient.builder(chatModel);
            if (!CollectionUtils.isEmpty(advisors)) builder.defaultAdvisors(advisors);
            this.chatClient = builder.defaultOptions(toolOptions).defaultToolCallbacks(tools).build();
        } catch (Exception e) {
            throw new RuntimeException("ChatClient 初始化失败：" + e.getMessage(), e);
        }
    }

    @Override
    public Flux<String> execute(String conversationId, String question) {
        return streamInternal(conversationId, question, true);
    }

    public Flux<String> stream(String question) { return streamInternal(null, question, true); }
    public Flux<String> stream(String conversationId, String question) { return streamInternal(conversationId, question, true); }
    public Flux<String> stream(String conversationId, String question, boolean enableThinking) {
        return streamInternal(conversationId, question, enableThinking);
    }

    private Flux<String> streamInternal(String conversationId, String question, boolean enableThinking) {
        List<Message> messages = Collections.synchronizedList(new ArrayList<>());
        boolean useMemory = conversationId != null && chatMemory != null;
        if (StringUtils.isBlank(conversationId)) conversationId = UUID.randomUUID().toString();

        Flux<String> checkResult = checkRunningTask(conversationId);
        if (checkResult != null) return checkResult;

        initTimers();
        clearUsedTools();

        Sinks.Many<String> sink = Sinks.many().unicast().onBackpressureBuffer();

        AgentTaskManager.TaskInfo taskInfo = registerTask(conversationId, sink);
        if (taskInfo == null && conversationId != null && taskManager != null) {
            return Flux.error(new IllegalStateException("该会话正在执行中，请稍后再试"));
        }

        if (StringUtils.isNotBlank(systemPrompt)) {
            messages.add(new SystemMessage(systemPrompt));
        } else {
            messages.add(new SystemMessage(ReactAgentPrompts.getDearAgentPrompt()));
        }

        loadChatHistory(conversationId, messages, true, true);
        messages.add(new UserMessage("<question>" + question + "</question>"));
        currentQuestion = question;

        if (sessionService != null) {
            AiSession savedSession = sessionService.saveQuestion(
                    SaveQuestionRequest.builder().sessionId(conversationId).question(question).build());
            currentSessionId = savedSession.getId();
        }

        AtomicLong roundCounter = new AtomicLong(0);
        AtomicBoolean hasSentFinalResult = new AtomicBoolean(false);
        hasSentFinalResult.set(false);
        roundCounter.set(0);

        StringBuilder finalAnswerBuffer = new StringBuilder();
        StringBuilder thinkingBuffer = new StringBuilder();
        AgentState agentState = new AgentState();

        scheduleRound(messages, sink, roundCounter, hasSentFinalResult, finalAnswerBuffer, useMemory, conversationId, agentState, thinkingBuffer, enableThinking);

        String finalConversationId = conversationId;
        return sink.asFlux()
                .doOnNext(chunk -> {
                    recordFirstResponse();
                    try {
                        JSONObject json = JSON.parseObject(chunk);
                        String type = json.getString("type");
                        if ("text".equals(type)) finalAnswerBuffer.append(json.getString("content"));
                    } catch (Exception e) { finalAnswerBuffer.append(chunk); }
                })
                .doOnCancel(() -> {
                    hasSentFinalResult.set(true);
                    if (taskManager != null) taskManager.stopTask(finalConversationId);
                })
                .doFinally(signalType -> {
                    log.info("最终答案: {}", finalAnswerBuffer);
                    log.info("思考过程: {}", thinkingBuffer);
                    saveSessionResult(finalConversationId, finalAnswerBuffer, thinkingBuffer, agentState);
                    if (taskManager != null) taskManager.stopTask(finalConversationId);
                });
    }

    private void saveSessionResult(String conversationId, StringBuilder finalAnswerBuffer, StringBuilder thinkingBuffer, AgentState agentState) {
        if (sessionService != null && currentSessionId != null && finalAnswerBuffer.length() > 0) {
            long totalResponseTime = getTotalResponseTime();
            String toolsStr = getUsedToolsString();
            String referenceJson = "";
            if (!agentState.searchResults.isEmpty()) referenceJson = createReferenceResponse(JSON.toJSONString(agentState.searchResults));
            UpdateAnswerRequest request = UpdateAnswerRequest.builder()
                    .id(currentSessionId)
                    .answer(finalAnswerBuffer.toString())
                    .thinking(thinkingBuffer.toString())
                    .tools(toolsStr)
                    .reference(referenceJson)
                    .recommend(currentRecommendations)
                    .firstResponseTime(firstResponseTime)
                    .totalResponseTime(totalResponseTime)
                    .build();
            sessionService.updateAnswer(request);
            log.info("结果已保存到会话: sessionId={}", conversationId);
        }
    }

    private void scheduleRound(List<Message> messages, Sinks.Many<String> sink, AtomicLong roundCounter, AtomicBoolean hasSentFinalResult,
                               StringBuilder finalAnswerBuffer, boolean useMemory, String conversationId, AgentState agentState,
                               StringBuilder thinkingBuffer, boolean enableThinking) {
        roundCounter.incrementAndGet();
        RoundState state = new RoundState();

        Disposable disposable = chatClient.prompt()
                .messages(messages)
                .stream()
                .chatResponse()
                .publishOn(Schedulers.boundedElastic())
                .doOnNext(chunk -> processChunk(chunk, sink, state, thinkingBuffer, enableThinking))
                .doOnComplete(() -> finishRound(messages, sink, state, roundCounter, hasSentFinalResult, finalAnswerBuffer, useMemory, conversationId, agentState, thinkingBuffer, enableThinking))
                .doOnError(err -> { if (!hasSentFinalResult.get()) { hasSentFinalResult.set(true); sink.tryEmitError(err); } })
                .subscribe();

        if (conversationId != null && taskManager != null) taskManager.setDisposable(conversationId, disposable);
    }

    private void processChunk(ChatResponse chunk, Sinks.Many<String> sink, RoundState state, StringBuilder thinkingBuffer, boolean enableThinking) {
        if (chunk == null || chunk.getResult() == null || chunk.getResult().getOutput() == null) return;
        Generation gen = chunk.getResult();
        String text = gen.getOutput().getText();
        List<AssistantMessage.ToolCall> tc = gen.getOutput().getToolCalls();

        if (enableThinking && text == null) {
            String thinkingText = extractThinkingText(gen);
            if (StringUtils.isNotBlank(thinkingText)) {
                thinkingBuffer.append(thinkingText);
                if (enableThinking) sink.tryEmitNext(createThinkingResponse(thinkingText));
            }
        }

        if (tc != null && !tc.isEmpty()) {
            state.mode = RoundMode.TOOL_CALL;
            for (AssistantMessage.ToolCall incoming : tc) mergeToolCall(state, incoming);
            return;
        }

        if (text != null) { sink.tryEmitNext(createTextResponse(text)); state.textBuffer.append(text); }
    }

    private void mergeToolCall(RoundState state, AssistantMessage.ToolCall incoming) {
        for (int i = 0; i < state.toolCalls.size(); i++) {
            AssistantMessage.ToolCall existing = state.toolCalls.get(i);
            if (existing.id().equals(incoming.id())) {
                String mergedArgs = Objects.toString(existing.arguments(), "") + Objects.toString(incoming.arguments(), "");
                state.toolCalls.set(i, new AssistantMessage.ToolCall(existing.id(), "function", existing.name(), mergedArgs));
                return;
            }
        }
        state.toolCalls.add(incoming);
    }

    private void finishRound(List<Message> messages, Sinks.Many<String> sink, RoundState state,
                             AtomicLong roundCounter, AtomicBoolean hasSentFinalResult, StringBuilder finalAnswerBuffer,
                             boolean useMemory, String conversationId, AgentState agentState,
                             StringBuilder thinkingBuffer, boolean enableThinking) {
        if (state.getMode() != RoundMode.TOOL_CALL) {
            String referenceJson = "";
            String finalText = state.textBuffer.toString();

            if (!agentState.searchResults.isEmpty()) {
                String reference = JSON.toJSONString(agentState.searchResults);
                referenceJson = createReferenceResponse(reference);
                sink.tryEmitNext(referenceJson);
            }

            if (enableRecommendations) {
                String recommendations = generateRecommendations(conversationId, currentQuestion, finalText);
                if (recommendations != null) {
                    currentRecommendations = recommendations;
                    sink.tryEmitNext(createRecommendResponse(recommendations));
                }
            }

            sink.tryEmitNext(createDoneResponse(conversationId));
            sink.tryEmitComplete();
            hasSentFinalResult.set(true);
            return;
        }

        AssistantMessage assistantMsg = AssistantMessage.builder().toolCalls(state.toolCalls).build();
        messages.add(assistantMsg);

        if (maxRounds > 0 && roundCounter.get() >= maxRounds) {
            forceFinalStream(messages, sink, hasSentFinalResult, state, conversationId, useMemory, agentState, thinkingBuffer, enableThinking);
            return;
        }

        executeToolCalls(sink, state.toolCalls, messages, hasSentFinalResult, state, agentState, thinkingBuffer, enableThinking, () -> {
            if (!hasSentFinalResult.get()) {
                scheduleRound(messages, sink, roundCounter, hasSentFinalResult, finalAnswerBuffer, useMemory, conversationId, agentState, thinkingBuffer, enableThinking);
            }
        });
    }

    private void forceFinalStream(List<Message> messages, Sinks.Many<String> sink, AtomicBoolean hasSentFinalResult, RoundState state,
                                  String conversationId, boolean useMemory, AgentState agentState,
                                  StringBuilder thinkingBuffer, boolean enableThinking) {
        List<Message> newMessages = new ArrayList<>();
        if (StringUtils.isNotBlank(systemPrompt)) {
            newMessages.add(new SystemMessage(systemPrompt));
        } else {
            newMessages.add(new SystemMessage(ReactAgentPrompts.getWebSearchPrompt()));
        }
        for (Message msg : messages) { if (!(msg instanceof SystemMessage)) newMessages.add(msg); }
        newMessages.add(new UserMessage("""
                你已达到最大推理轮次限制。
                请基于当前已有的上下文信息，直接给出最终答案。
                禁止再调用任何工具。
                如果信息不完整，请合理总结和说明。
                """));
        messages.clear();
        messages.addAll(newMessages);

        StringBuilder finalTextBuffer = new StringBuilder();

        Disposable disposable = chatClient.prompt().messages(messages).stream().chatResponse()
                .publishOn(Schedulers.boundedElastic())
                .doOnNext(chunk -> {
                    if (chunk == null || chunk.getResult() == null || chunk.getResult().getOutput() == null) return;
                    String text = chunk.getResult().getOutput().getText();
                    if (text != null && !hasSentFinalResult.get()) { sink.tryEmitNext(createTextResponse(text)); finalTextBuffer.append(text); }
                })
                .doOnComplete(() -> {
                    String referenceJson = "";
                    String finalText = finalTextBuffer.toString();
                    if (!agentState.searchResults.isEmpty()) {
                        sink.tryEmitNext(createReferenceResponse(JSON.toJSONString(agentState.searchResults)));
                    }
                    if (enableRecommendations) {
                        String recommendations = generateRecommendations(conversationId, currentQuestion, finalText);
                        if (recommendations != null) { currentRecommendations = recommendations; sink.tryEmitNext(createRecommendResponse(recommendations)); }
                    }
                    sink.tryEmitNext(createDoneResponse(conversationId));
                    hasSentFinalResult.set(true);
                    sink.tryEmitComplete();
                })
                .doOnError(err -> { hasSentFinalResult.set(true); sink.tryEmitError(err); })
                .subscribe();

        if (conversationId != null && taskManager != null) taskManager.setDisposable(conversationId, disposable);
    }

    private void executeToolCalls(Sinks.Many<String> sink, List<AssistantMessage.ToolCall> toolCalls, List<Message> messages,
                                  AtomicBoolean hasSentFinalResult, RoundState state, AgentState agentState,
                                  StringBuilder thinkingBuffer, boolean enableThinking, Runnable onComplete) {
        AtomicInteger completedCount = new AtomicInteger(0);
        int totalToolCalls = toolCalls.size();
        Map<String, ToolResponseMessage.ToolResponse> responseMap = new ConcurrentHashMap<>();

        for (AssistantMessage.ToolCall tc : toolCalls) {
            Schedulers.boundedElastic().schedule(() -> {
                if (hasSentFinalResult.get()) { completeToolCall(completedCount, totalToolCalls, responseMap, toolCalls, messages, onComplete); return; }

                String toolName = tc.name();
                String argsJson = tc.arguments();

                ToolCallback callback = findTool(toolName);
                if (callback == null) {
                    responseMap.put(tc.id(), new ToolResponseMessage.ToolResponse(tc.id(), toolName, "{ \"error\": \"工具未找到：" + toolName + "\" }"));
                    completeToolCall(completedCount, totalToolCalls, responseMap, toolCalls, messages, onComplete);
                    return;
                }
                if (toolName.contains("search")) {
                    JSONObject args = JSON.parseObject(argsJson);
                    String query = (String) args.get("query");
                    String queryThink = StringUtils.isNotBlank(query) ? "🔍 正在搜索信息: " + query + "\n" : "🔍 正在搜索相关信息\n";
                    thinkingBuffer.append(queryThink);
                    if (enableThinking) sink.tryEmitNext(createThinkingResponse(queryThink));
                }

                try {
                    Object result = callback.call(argsJson);
                    String resultStr = result.toString();
                    recordUsedTool(toolName);
                    if (toolName.contains("tavily")) parseSearchResult(resultStr, agentState);
                    responseMap.put(tc.id(), new ToolResponseMessage.ToolResponse(tc.id(), toolName, resultStr));
                } catch (Exception ex) {
                    responseMap.put(tc.id(), new ToolResponseMessage.ToolResponse(tc.id(), toolName, "{ \"error\": \"工具执行失败：" + ex.getMessage() + "\" }"));
                } finally {
                    completeToolCall(completedCount, totalToolCalls, responseMap, toolCalls, messages, onComplete);
                }
            });
        }
    }

    private void completeToolCall(AtomicInteger completedCount, int total,
                                  Map<String, ToolResponseMessage.ToolResponse> responseMap,
                                  List<AssistantMessage.ToolCall> originalToolCalls,
                                  List<Message> messages, Runnable onComplete) {
        int current = completedCount.incrementAndGet();
        if (current >= total) {
            List<ToolResponseMessage.ToolResponse> sortedResponses = new ArrayList<>();
            for (AssistantMessage.ToolCall tc : originalToolCalls) {
                ToolResponseMessage.ToolResponse response = responseMap.get(tc.id());
                sortedResponses.add(response != null ? response : new ToolResponseMessage.ToolResponse(tc.id(), tc.name(), "{ \"error\": \"工具响应丢失\" }"));
            }
            messages.add(ToolResponseMessage.builder().responses(sortedResponses).build());
            onComplete.run();
        }
    }

    private void parseSearchResult(String resultJson, AgentState state) {
        try {
            JsonNode root = MAPPER.readTree(resultJson);
            if (!root.isArray() || root.isEmpty()) return;
            JsonNode first = root.get(0);
            JsonNode textNode = first.get("text");
            if (textNode == null || textNode.isNull()) return;
            JsonNode textJson = textNode.isTextual() ? MAPPER.readTree(textNode.asText()) : textNode;
            JsonNode results = textJson.get("results");
            if (results == null || !results.isArray()) return;
            for (JsonNode item : results) {
                String url = getSafe(item, "url");
                String title = getSafe(item, "title");
                String content = getSafe(item, "content");
                if (url != null && !url.isBlank()) state.searchResults.add(new SearchResult(url, title, content));
            }
        } catch (Exception e) { log.warn("解析 tavily 搜索结果失败: {}", e.getMessage()); }
    }

    private String getSafe(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    private void addErrorToolResponse(List<Message> messages, AssistantMessage.ToolCall toolCall, String errMsg) {
        messages.add(ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse(toolCall.id(), toolCall.name(), "{ \"error\": \"" + errMsg + "\" }")))
                .build());
    }

    private ToolCallback findTool(String name) {
        return tools.stream().filter(t -> t.getToolDefinition().name().equals(name)).findFirst().orElse(null);
    }

    private String extractThinkingText(Generation gen) {
        String thinking = extractThinkingFromMetadata(gen.getOutput().getMetadata());
        return StringUtils.isNotBlank(thinking) ? thinking : null;
    }

    private String extractThinkingFromMetadata(Map<String, Object> metadata) {
        if (metadata == null) return null;
        try { return firstNonBlankThinking(metadata); } catch (Exception ignored) { return null; }
    }

    private String firstNonBlankThinking(Map<String, Object> metadata) {
        String thinking = null;
        for (String key : List.of("reasoningContent", "reasoning", "thinking", "thought", "reasoning_content")) {
            thinking = extractString(metadata.get(key));
            if (StringUtils.isNotBlank(thinking)) return thinking;
        }
        return null;
    }

    private String extractString(Object value) {
        if (value == null) return null;
        if (value instanceof String str) return str;
        if (value instanceof Collection<?> collection) {
            StringBuilder sb = new StringBuilder();
            for (Object item : collection) {
                String text = extractString(item);
                if (StringUtils.isNotBlank(text)) { if (sb.length() > 0) sb.append('\n'); sb.append(text); }
            }
            return sb.toString();
        }
        if (value instanceof Map<?, ?> map) {
            for (String key : List.of("text", "content", "value")) {
                String text = extractString(map.get(key));
                if (StringUtils.isNotBlank(text)) return text;
            }
            return null;
        }
        return Objects.toString(value, null);
    }

    public void setMaxRounds(int maxRounds) { this.maxRounds = maxRounds; }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private String name;
        private ChatModel chatModel;
        private List<ToolCallback> tools;
        private String systemPrompt = "";
        private int maxReflectionRounds;
        private int maxRounds;
        private List<Advisor> advisors;
        private ChatMemory chatMemory;
        private AiSessionService sessionService;
        private AgentTaskManager taskManager;

        public Builder chatMemory(ChatMemory chatMemory) { this.chatMemory = chatMemory; return this; }
        public Builder sessionService(AiSessionService sessionService) { this.sessionService = sessionService; return this; }
        public Builder taskManager(AgentTaskManager taskManager) { this.taskManager = taskManager; return this; }
        public Builder name(String name) { this.name = name; return this; }
        public Builder chatModel(ChatModel chatModel) { this.chatModel = chatModel; return this; }
        public Builder tools(ToolCallback... tools) { this.tools = Arrays.asList(tools); return this; }
        public Builder tools(List<ToolCallback> tools) { this.tools = tools; return this; }
        public Builder advisors(List<Advisor> advisors) { this.advisors = advisors; return this; }
        public Builder advisors(Advisor... advisors) { this.advisors = Arrays.asList(advisors); return this; }
        public Builder systemPrompt(String systemPrompt) { this.systemPrompt = systemPrompt; return this; }
        public Builder maxReflectionRounds(int maxReflectionRounds) { this.maxReflectionRounds = maxReflectionRounds; return this; }
        public Builder maxRounds(int maxRounds) { this.maxRounds = maxRounds; return this; }

        public DearAgent build() {
            if (chatModel == null) throw new IllegalArgumentException("chatModel 不能为空！");
            return new DearAgent(name, chatModel, tools, systemPrompt, maxRounds, chatMemory, advisors, maxReflectionRounds, sessionService, taskManager);
        }
    }
}
