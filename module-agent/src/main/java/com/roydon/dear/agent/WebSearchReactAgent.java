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
import com.roydon.dear.session.entity.ChatMessage;
import com.roydon.dear.session.service.ChatConversationService;
import com.roydon.dear.session.service.ChatMessageService;
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
public class WebSearchReactAgent extends BaseAgent {

    private ChatClient chatClient;
    private final List<ToolCallback> tools;
    private final String systemPrompt;
    private int maxRounds;
    private final List<Advisor> advisors;
    private final int maxReflectionRounds;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public WebSearchReactAgent(String name, ChatModel chatModel, List<ToolCallback> tools, String systemPrompt, int maxRounds,
                               ChatMemory chatMemory, List<Advisor> advisors, int maxReflectionRounds,
                               ChatConversationService conversationService, ChatMessageService messageService,
                               AgentTaskManager taskManager) {
        super(name, chatModel, "websearch");
        this.tools = tools;
        this.systemPrompt = systemPrompt;
        this.maxRounds = maxRounds;
        this.advisors = advisors;
        this.maxReflectionRounds = maxReflectionRounds;
        this.chatMemory = chatMemory;
        this.conversationService = conversationService;
        this.messageService = messageService;
        this.taskManager = taskManager;
        this.usedTools = new HashSet<>();
        initChatClient();
        if (this.chatClient == null) throw new IllegalStateException("ChatClient 初始化失败！");
    }

    private void initChatClient() {
        try {
            ToolCallingChatOptions toolOptions = ToolCallingChatOptions.builder()
                    .toolCallbacks(tools).internalToolExecutionEnabled(false).build();
            ChatClient.Builder builder = ChatClient.builder(chatModel);
            if (!CollectionUtils.isEmpty(advisors)) builder.defaultAdvisors(advisors);
            this.chatClient = builder.defaultOptions(toolOptions).defaultToolCallbacks(tools).build();
        } catch (Exception e) {
            throw new RuntimeException("ChatClient 初始化失败：" + e.getMessage(), e);
        }
    }

    @Override
    public Flux<String> execute(String conversationId, String question) {
        return streamInternal(conversationId, question, null);
    }

    public Flux<String> stream(String question) {
        return streamInternal(null, question, null);
    }

    public Flux<String> stream(String conversationId, String question, String fileIds) {
        return streamInternal(conversationId, question, fileIds);
    }

    private Flux<String> streamInternal(String conversationId, String question, String fileIds) {
        List<Message> messages = Collections.synchronizedList(new ArrayList<>());
        boolean useMemory = conversationId != null && chatMemory != null;

        Flux<String> checkResult = checkRunningTask(conversationId);
        if (checkResult != null) return checkResult;

        initTimers();
        clearUsedTools();

        Sinks.Many<String> sink = Sinks.many().unicast().onBackpressureBuffer();

        AgentTaskManager.TaskInfo taskInfo = registerTask(conversationId, sink);
        if (taskInfo == null && conversationId != null && taskManager != null) {
            return Flux.error(new IllegalStateException("该会话正在执行中，请稍后再试"));
        }

        messages.add(new SystemMessage(ReactAgentPrompts.getWebSearchPrompt()));
        if (StringUtils.isNotBlank(systemPrompt)) messages.add(new SystemMessage(systemPrompt));

        loadChatHistory(conversationId, messages, true, true);
        messages.add(new UserMessage("<question>" + question + "</question>"));
        currentQuestion = question;

        if (conversationService != null && messageService != null) {
            String title = question.length() > 32 ? question.substring(0, 32) : question;
            com.roydon.dear.session.entity.ChatConversation conversation = conversationService.getOrCreateBySessionId(conversationId, title);
            currentConversationNumericId = conversation.getId();
            ChatMessage userMsg = messageService.saveUserMessage(conversation.getId(), question, null, fileIds);
            currentUserMessageId = userMsg.getId();
        }

        AtomicLong roundCounter = new AtomicLong(0);
        AtomicBoolean hasSentFinalResult = new AtomicBoolean(false);
        hasSentFinalResult.set(false);
        roundCounter.set(0);

        StringBuilder finalAnswerBuffer = new StringBuilder();
        StringBuilder thinkingBuffer = new StringBuilder();
        AgentState agentState = new AgentState();

        scheduleRound(messages, sink, roundCounter, hasSentFinalResult, finalAnswerBuffer, useMemory, conversationId, agentState, thinkingBuffer);

        return sink.asFlux()
                .doOnNext(chunk -> {
                    recordFirstResponse();
                    try {
                        JSONObject json = JSON.parseObject(chunk);
                        String type = json.getString("type");
                        if ("text".equals(type)) finalAnswerBuffer.append(json.getString("content"));
                        else if ("thinking".equals(type)) thinkingBuffer.append(json.getString("content"));
                    } catch (Exception e) {
                        finalAnswerBuffer.append(chunk);
                    }
                })
                .doOnCancel(() -> {
                    hasSentFinalResult.set(true);
                    if (taskManager != null) taskManager.stopTask(conversationId);
                })
                .doFinally(signalType -> {
                    log.info("最终答案: {}", finalAnswerBuffer);
                    log.info("思考过程: {}", thinkingBuffer);
                    saveSessionResult(conversationId, finalAnswerBuffer, thinkingBuffer, agentState, fileIds);
                    if (taskManager != null) taskManager.stopTask(conversationId);
                });
    }

    private void saveSessionResult(String conversationId, StringBuilder finalAnswerBuffer, StringBuilder thinkingBuffer, AgentState agentState, String fileIds) {
        if (conversationService != null && messageService != null && currentConversationNumericId != null
                && currentUserMessageId != null && finalAnswerBuffer.length() > 0) {
            long totalResponseTime = getTotalResponseTime();
            String toolsStr = getUsedToolsString();
            String referenceJson = "";
            if (!agentState.searchResults.isEmpty())
                referenceJson = createReferenceResponse(JSON.toJSONString(agentState.searchResults));
            messageService.saveAssistantMessage(
                    currentConversationNumericId, currentUserMessageId,
                    finalAnswerBuffer.toString(), thinkingBuffer.toString(),
                    toolsStr, referenceJson, currentRecommendations,
                    firstResponseTime, totalResponseTime, fileIds);
            String lastMsg = finalAnswerBuffer.length() > 64
                    ? finalAnswerBuffer.substring(0, 64) : finalAnswerBuffer.toString();
            conversationService.updateLastMessage(currentConversationNumericId, lastMsg);
            log.info("结果已保存到会话: sessionId={}", conversationId);
        }
    }

    private void scheduleRound(List<Message> messages, Sinks.Many<String> sink, AtomicLong roundCounter, AtomicBoolean hasSentFinalResult,
                               StringBuilder finalAnswerBuffer, boolean useMemory, String conversationId, AgentState agentState,
                               StringBuilder thinkingBuffer) {
        roundCounter.incrementAndGet();
        RoundState state = new RoundState();

        Disposable disposable = chatClient.prompt().messages(messages).stream().chatResponse()
                .publishOn(Schedulers.boundedElastic())
                .doOnNext(chunk -> processChunk(chunk, sink, state))
                .doOnComplete(() -> finishRound(messages, sink, state, roundCounter, hasSentFinalResult, finalAnswerBuffer, useMemory, conversationId, agentState, thinkingBuffer))
                .doOnError(err -> {
                    if (!hasSentFinalResult.get()) {
                        hasSentFinalResult.set(true);
                        sink.tryEmitError(err);
                    }
                })
                .subscribe();

        if (conversationId != null && taskManager != null) taskManager.setDisposable(conversationId, disposable);
    }

    private void processChunk(ChatResponse chunk, Sinks.Many<String> sink, RoundState state) {
        if (chunk == null || chunk.getResult() == null || chunk.getResult().getOutput() == null) return;
        Generation gen = chunk.getResult();
        String text = gen.getOutput().getText();
        List<AssistantMessage.ToolCall> tc = gen.getOutput().getToolCalls();

        if (tc != null && !tc.isEmpty()) {
            state.mode = RoundMode.TOOL_CALL;
            for (AssistantMessage.ToolCall incoming : tc) mergeToolCall(state, incoming);
            return;
        }
        if (text != null) {
            sink.tryEmitNext(createTextResponse(text));
            state.textBuffer.append(text);
        }
    }

    private void mergeToolCall(RoundState state, AssistantMessage.ToolCall incoming) {
        for (int i = 0; i < state.toolCalls.size(); i++) {
            AssistantMessage.ToolCall existing = state.toolCalls.get(i);
            if (existing.id().equals(incoming.id())) {
                state.toolCalls.set(i, new AssistantMessage.ToolCall(existing.id(), "function", existing.name(),
                        Objects.toString(existing.arguments(), "") + Objects.toString(incoming.arguments(), "")));
                return;
            }
        }
        state.toolCalls.add(incoming);
    }

    private void finishRound(List<Message> messages, Sinks.Many<String> sink, RoundState state,
                             AtomicLong roundCounter, AtomicBoolean hasSentFinalResult, StringBuilder finalAnswerBuffer,
                             boolean useMemory, String conversationId, AgentState agentState, StringBuilder thinkingBuffer) {
        if (state.getMode() != RoundMode.TOOL_CALL) {
            String finalText = state.textBuffer.toString();
            if (!agentState.searchResults.isEmpty())
                sink.tryEmitNext(createReferenceResponse(JSON.toJSONString(agentState.searchResults)));
            if (enableRecommendations) {
                String recommendations = generateRecommendations(conversationId, currentQuestion, finalText);
                if (recommendations != null) {
                    currentRecommendations = recommendations;
                    sink.tryEmitNext(createRecommendResponse(recommendations));
                }
            }
            sink.tryEmitComplete();
            hasSentFinalResult.set(true);
            return;
        }

        AssistantMessage assistantMsg = AssistantMessage.builder().toolCalls(state.toolCalls).build();
        messages.add(assistantMsg);

        if (maxRounds > 0 && roundCounter.get() >= maxRounds) {
            forceFinalStream(messages, sink, hasSentFinalResult, state, conversationId, useMemory, agentState, thinkingBuffer);
            return;
        }

        executeToolCalls(sink, state.toolCalls, messages, hasSentFinalResult, state, agentState, () -> {
            if (!hasSentFinalResult.get())
                scheduleRound(messages, sink, roundCounter, hasSentFinalResult, finalAnswerBuffer, useMemory, conversationId, agentState, thinkingBuffer);
        });
    }

    private void forceFinalStream(List<Message> messages, Sinks.Many<String> sink, AtomicBoolean hasSentFinalResult, RoundState state,
                                  String conversationId, boolean useMemory, AgentState agentState, StringBuilder thinkingBuffer) {
        List<Message> newMessages = new ArrayList<>();
        newMessages.add(new SystemMessage(ReactAgentPrompts.getWebSearchPrompt()));
        if (StringUtils.isNotBlank(systemPrompt)) newMessages.add(new SystemMessage(systemPrompt));
        for (Message msg : messages) {
            if (!(msg instanceof SystemMessage)) newMessages.add(msg);
        }
        newMessages.add(new UserMessage("""
                你已达到最大推理轮次限制。请基于当前已有的上下文信息，直接给出最终答案。
                禁止再调用任何工具。如果信息不完整，请合理总结和说明。
                """));
        messages.clear();
        messages.addAll(newMessages);

        StringBuilder finalTextBuffer = new StringBuilder();

        Disposable disposable = chatClient.prompt().messages(messages).stream().chatResponse()
                .publishOn(Schedulers.boundedElastic())
                .doOnNext(chunk -> {
                    if (chunk == null || chunk.getResult() == null || chunk.getResult().getOutput() == null) return;
                    String text = chunk.getResult().getOutput().getText();
                    if (text != null && !hasSentFinalResult.get()) {
                        sink.tryEmitNext(createTextResponse(text));
                        finalTextBuffer.append(text);
                    }
                })
                .doOnComplete(() -> {
                    String finalText = finalTextBuffer.toString();
                    if (!agentState.searchResults.isEmpty())
                        sink.tryEmitNext(createReferenceResponse(JSON.toJSONString(agentState.searchResults)));
                    if (enableRecommendations) {
                        String recommendations = generateRecommendations(conversationId, currentQuestion, finalText);
                        if (recommendations != null) {
                            currentRecommendations = recommendations;
                            sink.tryEmitNext(createRecommendResponse(recommendations));
                        }
                    }
                    hasSentFinalResult.set(true);
                    sink.tryEmitComplete();
                })
                .doOnError(err -> {
                    hasSentFinalResult.set(true);
                    sink.tryEmitError(err);
                })
                .subscribe();

        if (conversationId != null && taskManager != null) taskManager.setDisposable(conversationId, disposable);
    }

    private void executeToolCalls(Sinks.Many<String> sink, List<AssistantMessage.ToolCall> toolCalls, List<Message> messages,
                                  AtomicBoolean hasSentFinalResult, RoundState state, AgentState agentState, Runnable onComplete) {
        AtomicInteger completedCount = new AtomicInteger(0);
        int totalToolCalls = toolCalls.size();
        Map<String, ToolResponseMessage.ToolResponse> responseMap = new ConcurrentHashMap<>();

        for (AssistantMessage.ToolCall tc : toolCalls) {
            Schedulers.boundedElastic().schedule(() -> {
                if (hasSentFinalResult.get()) {
                    completeToolCall(completedCount, totalToolCalls, responseMap, toolCalls, messages, onComplete);
                    return;
                }

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
                    sink.tryEmitNext(createThinkingResponse(queryThink));
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
                                  List<AssistantMessage.ToolCall> originalToolCalls, List<Message> messages, Runnable onComplete) {
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
        } catch (Exception e) {
            log.warn("解析 tavily 搜索结果失败: {}", e.getMessage());
        }
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

    public void setMaxRounds(int maxRounds) {
        this.maxRounds = maxRounds;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String name;
        private ChatModel chatModel;
        private List<ToolCallback> tools;
        private String systemPrompt = "";
        private int maxReflectionRounds;
        private int maxRounds;
        private List<Advisor> advisors;
        private ChatMemory chatMemory;
        private ChatConversationService conversationService;
        private ChatMessageService messageService;
        private AgentTaskManager taskManager;

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

        public Builder advisors(List<Advisor> advisors) {
            this.advisors = advisors;
            return this;
        }

        public Builder advisors(Advisor... advisors) {
            this.advisors = Arrays.asList(advisors);
            return this;
        }

        public Builder systemPrompt(String systemPrompt) {
            this.systemPrompt = systemPrompt;
            return this;
        }

        public Builder maxReflectionRounds(int maxReflectionRounds) {
            this.maxReflectionRounds = maxReflectionRounds;
            return this;
        }

        public Builder maxRounds(int maxRounds) {
            this.maxRounds = maxRounds;
            return this;
        }

        public WebSearchReactAgent build() {
            if (chatModel == null) throw new IllegalArgumentException("chatModel 不能为空！");
            return new WebSearchReactAgent(name, chatModel, tools, systemPrompt, maxRounds, chatMemory, advisors, maxReflectionRounds, conversationService, messageService, taskManager);
        }
    }
}
