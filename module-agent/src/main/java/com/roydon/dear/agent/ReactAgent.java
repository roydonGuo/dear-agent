package com.roydon.dear.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.roydon.dear.common.domain.agent.AgentState;
import com.roydon.dear.common.domain.agent.RoundMode;
import com.roydon.dear.common.domain.agent.SearchResult;
import com.roydon.dear.common.domain.agent.SimpleReactResult;
import com.roydon.dear.common.prompts.PlanExecutePrompts;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.util.CollectionUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
public class ReactAgent {

    public static final String REACT_AGENT_SYSTEM_PROMPT = """
            ## 角色
            你是一个联网查询助手，擅长用联网查询工具，查询准确的信息，过滤掉无效的广告。

            ## 工具调用规则（极其重要）
            1. 如果需要调用工具：必须使用 OpenAI 官方 ToolCall 结构，并且 **只能通过工具调用字段输出**。
            2. 工具调用时：**禁止在 content 中出现任何形式的工具调用文本**（包括 JSON、<tool_call>、函数名、参数、思考、推理或描述）。
            3. 工具调用消息必须是一次性、原子性输出，不得混杂任何解释或内容。
            4. 工具调用前后不得输出任何多余文字、标签、换行、推理轨迹或说明。
            5. 调用工具时：
               -工具参数必须是有效的JSON
               -参数必须简洁，不超过500个字符
               -切勿包含以前的工具结果、原始内容、HTML或长文本
               -仅包括工具所需的最小控制参数

            ## 工具执行结果
            系统会自动将工具执行结果作为 ToolResponseMessage 注入上下文，你只需读取并决定下一步动作。

            ## 最终答案规则
            1. 如果上下文已经拥有了完成任务的全部信息，则不要再调用任何工具。
            2. 在这种情况下，你必须输出最终自然语言答案，且 **禁止包含任何工具调用格式**。
            3. 最终答案只允许是自然语言，不能包含 JSON、思考过程、reasoning、ToolCall 或伪代码。

            ## 强制要求（必须遵守）
            1. 工具调用消息必须只通过 ToolCall 字段输出，不允许在 content 字段体现工具调用迹象。
            2. 如果本轮没有工具调用，则视为任务完成，你必须输出最终答案。
            3. 不允许重复调用同一个工具（名称 + 参数完全一致），除非工具调用失败。
            4. 禁止输出会干扰工具系统解析的任何结构（如 <reason>、<ToolCall>、函数 JSON、或模型内部思考）。
            5. 如果上下文已经包含了完成任务的全部信息，则不要再调用任何工具。
            """;

    private final String name;
    private final ChatModel chatModel;
    private final List<ToolCallback> tools;
    private final String systemPrompt;
    private ChatClient chatClient;
    private int maxRounds;
    private ChatMemory chatMemory;
    private List<Advisor> advisors;
    private int maxReflectionRounds;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public ReactAgent(String name, ChatModel chatModel, List<ToolCallback> tools, String systemPrompt, int maxRounds,
                      ChatMemory chatMemory, List<Advisor> advisors, int maxReflectionRounds) {
        this.name = name;
        this.chatModel = chatModel;
        this.tools = tools;
        this.systemPrompt = systemPrompt;
        this.maxRounds = maxRounds;
        this.chatMemory = chatMemory;
        this.maxReflectionRounds = maxReflectionRounds;
        this.advisors = advisors;
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
        } catch (Exception e) { throw new RuntimeException("ChatClient 初始化失败：" + e.getMessage(), e); }
    }

    public String call(String question) { return callInternal(null, question); }
    public String call(String conversationId, String question) { return callInternal(conversationId, question); }

    public String callInternal(String conversationId, String question) {
        List<Message> messages = Collections.synchronizedList(new ArrayList<>());
        boolean useMemory = conversationId != null && chatMemory != null;

        messages.add(new SystemMessage(REACT_AGENT_SYSTEM_PROMPT));
        messages.add(new SystemMessage(systemPrompt));

        if (useMemory) {
            List<Message> history = chatMemory.get(conversationId);
            if (history != null && !history.isEmpty()) messages.addAll(history);
        }
        messages.add(new UserMessage("<question>" + question + "</question>"));
        if (useMemory) chatMemory.add(conversationId, new UserMessage(question));

        int round = 0;
        while (true) {
            round++;
            if (maxRounds > 0 && round > maxRounds) {
                log.warn("=== 达到 maxRounds（{}），强制生成最终答案 ===", maxRounds);
                messages.add(new UserMessage("""
                        你已达到最大推理轮次限制。请基于当前已有的上下文信息，直接给出最终答案。
                        禁止再调用任何工具。如果信息不完整，请合理总结和说明。
                        """));
                String finalText = chatClient.prompt().messages(messages).call().content();
                if (useMemory) chatMemory.add(conversationId, new AssistantMessage(finalText));
                return finalText;
            }

            ChatClientResponse chatResponse = chatClient.prompt().messages(messages).call().chatClientResponse();
            String aiText = chatResponse.chatResponse().getResult().getOutput().getText();

            if (!chatResponse.chatResponse().hasToolCalls()) {
                if (useMemory) chatMemory.add(conversationId, new AssistantMessage(aiText));
                return aiText;
            }

            messages.add(AssistantMessage.builder()
                    .toolCalls(chatResponse.chatResponse().getResult().getOutput().getToolCalls()).build());

            chatResponse.chatResponse().getResult().getOutput().getToolCalls().forEach(toolCall -> {
                ToolCallback callback = findTool(toolCall.name());
                if (callback == null) { addErrorToolResponse(messages, toolCall, "工具未找到：" + toolCall.name()); return; }
                try {
                    Object result = callback.call(toolCall.arguments());
                    messages.add(ToolResponseMessage.builder()
                            .responses(List.of(new ToolResponseMessage.ToolResponse(toolCall.id(), toolCall.name(), result.toString())))
                            .build());
                } catch (Exception ex) { addErrorToolResponse(messages, toolCall, "工具执行失败：" + ex.getMessage()); }
            });
        }
    }

    private static class RoundState {
        RoundMode mode = RoundMode.UNKNOWN;
        StringBuilder textBuffer = new StringBuilder();
        List<AssistantMessage.ToolCall> toolCalls = Collections.synchronizedList(new ArrayList<>());
    }

    public Flux<String> stream(String question) { return streamInternal(null, question); }
    public Flux<String> stream(String conversationId, String question) { return streamInternal(conversationId, question); }

    public Flux<String> streamInternal(String conversationId, String question) {
        List<Message> messages = Collections.synchronizedList(new ArrayList<>());
        boolean useMemory = conversationId != null && chatMemory != null;

        messages.add(new SystemMessage(REACT_AGENT_SYSTEM_PROMPT));
        messages.add(new SystemMessage(systemPrompt));

        if (useMemory) {
            List<Message> history = chatMemory.get(conversationId);
            if (history != null && !history.isEmpty()) messages.addAll(history);
        }
        messages.add(new UserMessage("<question>" + question + "</question>"));
        if (useMemory) chatMemory.add(conversationId, new UserMessage(question));

        Sinks.Many<String> sink = Sinks.many().unicast().onBackpressureBuffer();
        AtomicLong roundCounter = new AtomicLong(0);
        AtomicBoolean hasSentFinalResult = new AtomicBoolean(false);
        hasSentFinalResult.set(false);
        roundCounter.set(0);

        StringBuilder finalAnswerBuffer = new StringBuilder();
        scheduleRound(messages, sink, roundCounter, hasSentFinalResult, finalAnswerBuffer, useMemory, conversationId);

        return sink.asFlux()
                .doOnNext(finalAnswerBuffer::append)
                .doOnCancel(() -> hasSentFinalResult.set(true))
                .doFinally(signalType -> log.info("最终答案: {}", finalAnswerBuffer));
    }

    private void scheduleRound(List<Message> messages, Sinks.Many<String> sink, AtomicLong roundCounter,
                               AtomicBoolean hasSentFinalResult, StringBuilder finalAnswerBuffer,
                               boolean useMemory, String conversationId) {
        roundCounter.incrementAndGet();
        RoundState state = new RoundState();

        chatClient.prompt().messages(messages).stream().chatResponse()
                .publishOn(Schedulers.boundedElastic())
                .doOnNext(chunk -> processChunk(chunk, sink, state))
                .doOnComplete(() -> finishRound(messages, sink, state, roundCounter, hasSentFinalResult, finalAnswerBuffer, useMemory, conversationId))
                .doOnError(err -> { if (!hasSentFinalResult.get()) { hasSentFinalResult.set(true); sink.tryEmitError(err); } })
                .subscribe();
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
        if (text != null) { sink.tryEmitNext(text); state.textBuffer.append(text); }
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

    private void finishRound(List<Message> messages, Sinks.Many<String> sink, RoundState state, AtomicLong roundCounter,
                             AtomicBoolean hasSentFinalResult, StringBuilder finalAnswerBuffer, boolean useMemory, String conversationId) {
        if (state.mode != RoundMode.TOOL_CALL) {
            sink.tryEmitComplete();
            hasSentFinalResult.set(true);
            if (useMemory) chatMemory.add(conversationId, new AssistantMessage(state.textBuffer.toString()));
            return;
        }

        if (maxRounds > 0 && roundCounter.get() >= maxRounds) {
            forceFinalStream(conversationId, useMemory, messages, sink, hasSentFinalResult);
            return;
        }

        messages.add(AssistantMessage.builder().toolCalls(state.toolCalls).build());

        executeToolCalls(state.toolCalls, messages, hasSentFinalResult, null, () -> {
            if (!hasSentFinalResult.get()) scheduleRound(messages, sink, roundCounter, hasSentFinalResult, finalAnswerBuffer, useMemory, conversationId);
        });
    }

    private void forceFinalStream(String conversationId, boolean useMemory, List<Message> messages,
                                  Sinks.Many<String> sink, AtomicBoolean hasSentFinalResult) {
        messages.add(new UserMessage("""
                你已达到最大推理轮次限制。请基于当前已有的上下文信息，直接给出最终答案。
                禁止再调用任何工具。如果信息不完整，请合理总结和说明。
                """));
        StringBuilder stringBuilder = new StringBuilder();

        chatClient.prompt().messages(messages).stream().chatResponse()
                .publishOn(Schedulers.boundedElastic())
                .doOnNext(chunk -> {
                    if (chunk == null || chunk.getResult() == null || chunk.getResult().getOutput() == null) return;
                    String text = chunk.getResult().getOutput().getText();
                    if (text != null && !hasSentFinalResult.get()) { sink.tryEmitNext(text); stringBuilder.append(text); }
                })
                .doOnComplete(() -> {
                    hasSentFinalResult.set(true);
                    sink.tryEmitComplete();
                    if (useMemory) chatMemory.add(conversationId, new AssistantMessage(stringBuilder.toString()));
                })
                .doOnError(err -> { hasSentFinalResult.set(true); sink.tryEmitError(err); })
                .subscribe();
    }

    private void executeToolCalls(List<AssistantMessage.ToolCall> toolCalls, List<Message> messages,
                                  AtomicBoolean hasSentFinalResult, AgentState agentState, Runnable onComplete) {
        AtomicInteger completedCount = new AtomicInteger(0);
        int totalToolCalls = toolCalls.size();
        Map<String, ToolResponseMessage.ToolResponse> responseMap = new ConcurrentHashMap<>();

        for (AssistantMessage.ToolCall tc : toolCalls) {
            Schedulers.boundedElastic().schedule(() -> {
                if (hasSentFinalResult.get()) { completeToolCall(completedCount, totalToolCalls, responseMap, toolCalls, messages, onComplete); return; }

                ToolCallback callback = findTool(tc.name());
                if (callback == null) {
                    responseMap.put(tc.id(), new ToolResponseMessage.ToolResponse(tc.id(), tc.name(), "{ \"error\": \"工具未找到：" + tc.name() + "\" }"));
                    completeToolCall(completedCount, totalToolCalls, responseMap, toolCalls, messages, onComplete);
                    return;
                }
                try {
                    Object result = callback.call(tc.arguments());
                    String resultStr = Objects.toString(result, "");
                    if (agentState != null) parseSearchResult(resultStr, agentState);
                    responseMap.put(tc.id(), new ToolResponseMessage.ToolResponse(tc.id(), tc.name(), resultStr));
                } catch (Exception ex) {
                    responseMap.put(tc.id(), new ToolResponseMessage.ToolResponse(tc.id(), tc.name(), "{ \"error\": \"工具执行失败：" + ex.getMessage() + "\" }"));
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

    private void addErrorToolResponse(List<Message> messages, AssistantMessage.ToolCall toolCall, String errMsg) {
        messages.add(ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse(toolCall.id(), toolCall.name(), "{ \"error\": \"" + errMsg + "\" }")))
                .build());
    }

    private ToolCallback findTool(String name) {
        return tools.stream().filter(t -> t.getToolDefinition().name().equals(name)).findFirst().orElse(null);
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
        } catch (Exception e) { log.warn("解析搜索结果失败: {}", e.getMessage()); }
    }

    private String getSafe(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    public SimpleReactResult callWithReference(String conversationId, String question) {
        return executeInternal(conversationId, question, true);
    }

    private SimpleReactResult executeInternal(String conversationId, String question, boolean withReference) {
        List<Message> messages = Collections.synchronizedList(new ArrayList<>());
        boolean useMemory = conversationId != null && chatMemory != null;
        AgentState agentState = withReference ? new AgentState() : null;

        messages.add(new SystemMessage(PlanExecutePrompts.getCurrentTime()));
        messages.add(new SystemMessage(REACT_AGENT_SYSTEM_PROMPT));
        messages.add(new SystemMessage(systemPrompt));

        if (useMemory) {
            List<Message> history = chatMemory.get(conversationId);
            if (history != null && !history.isEmpty()) messages.addAll(history);
        }
        messages.add(new UserMessage("<question>" + question + "</question>"));
        if (useMemory) chatMemory.add(conversationId, new UserMessage(question));

        int round = 0;
        while (true) {
            round++;
            if (maxRounds > 0 && round > maxRounds) {
                log.warn("=== 达到 maxRounds（{}），强制生成最终答案 ===", maxRounds);
                messages.add(new UserMessage("""
                        你已达到最大推理轮次限制。请基于当前已有的上下文信息，直接给出最终答案。
                        禁止再调用任何工具。如果信息不完整，请合理总结和说明。
                        """));
                String forcedAnswer = chatClient.prompt().messages(messages).call().content();
                if (useMemory) chatMemory.add(conversationId, new AssistantMessage(forcedAnswer));
                return SimpleReactResult.builder()
                        .answer(forcedAnswer)
                        .searchResults(agentState != null ? agentState.searchResults : Collections.emptyList())
                        .build();
            }

            ChatClientResponse chatResponse = chatClient.prompt().messages(messages).call().chatClientResponse();

            if (!chatResponse.chatResponse().hasToolCalls()) {
                String finalText = chatResponse.chatResponse().getResult().getOutput().getText();
                if (useMemory) chatMemory.add(conversationId, new AssistantMessage(finalText));
                return SimpleReactResult.builder()
                        .answer(finalText)
                        .searchResults(agentState != null ? agentState.searchResults : Collections.emptyList())
                        .build();
            }

            List<AssistantMessage.ToolCall> toolCalls = chatResponse.chatResponse().getResult().getOutput().getToolCalls();
            messages.add(AssistantMessage.builder().toolCalls(toolCalls).build());

            for (AssistantMessage.ToolCall toolCall : toolCalls) {
                ToolCallback callback = findTool(toolCall.name());
                if (callback == null) { addErrorToolResponse(messages, toolCall, "工具未找到：" + toolCall.name()); continue; }
                try {
                    Object result = callback.call(toolCall.arguments());
                    String resultStr = Objects.toString(result, "");
                    if (agentState != null) parseSearchResult(resultStr, agentState);
                    messages.add(ToolResponseMessage.builder()
                            .responses(List.of(new ToolResponseMessage.ToolResponse(toolCall.id(), toolCall.name(), resultStr)))
                            .build());
                } catch (Exception ex) { addErrorToolResponse(messages, toolCall, "工具执行失败：" + ex.getMessage()); }
            }
        }
    }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private String name; private ChatModel chatModel; private List<ToolCallback> tools; private String systemPrompt = "";
        private int maxReflectionRounds; private int maxRounds; private List<Advisor> advisors; private ChatMemory chatMemory;

        public Builder chatMemory(ChatMemory chatMemory) { this.chatMemory = chatMemory; return this; }
        public Builder name(String name) { this.name = name; return this; }
        public Builder chatModel(ChatModel chatModel) { this.chatModel = chatModel; return this; }
        public Builder tools(ToolCallback... tools) { this.tools = Arrays.asList(tools); return this; }
        public Builder tools(List<ToolCallback> tools) { this.tools = tools; return this; }
        public Builder advisors(List<Advisor> advisors) { this.advisors = advisors; return this; }
        public Builder advisors(Advisor... advisors) { this.advisors = Arrays.asList(advisors); return this; }
        public Builder systemPrompt(String systemPrompt) { this.systemPrompt = systemPrompt; return this; }
        public Builder maxReflectionRounds(int maxReflectionRounds) { this.maxReflectionRounds = maxReflectionRounds; return this; }
        public Builder maxRounds(int maxRounds) { this.maxRounds = maxRounds; return this; }

        public ReactAgent build() {
            if (chatModel == null) throw new IllegalArgumentException("chatModel 不能为空！");
            return new ReactAgent(name, chatModel, tools, systemPrompt, maxRounds, chatMemory, advisors, maxReflectionRounds);
        }
    }
}
