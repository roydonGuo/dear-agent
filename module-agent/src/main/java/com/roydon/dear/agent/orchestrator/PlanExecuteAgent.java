package com.roydon.dear.agent.orchestrator;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.roydon.dear.agent.BaseAgent;
import com.roydon.dear.agent.registry.AgentRegistry;
import com.roydon.dear.common.AgentResponse;
import com.roydon.dear.common.manager.AgentTaskManager;
import com.roydon.dear.common.prompts.PlanExecutePrompts;
import com.roydon.dear.session.entity.ChatConversation;
import com.roydon.dear.session.entity.ChatMessage;
import com.roydon.dear.session.service.ChatConversationService;
import com.roydon.dear.session.service.ChatMessageService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Plan-Execute Agent：先制定计划，再逐步执行，最后总结。
 *
 * 流程：
 *   1. PLAN  — LLM 生成 JSON 格式的步骤计划
 *   2. EXECUTE — 按 order 分组，同 order 并行执行，逐个 step 流式输出
 *   3. SUMMARIZE — 汇总所有步骤结果，生成最终报告
 */
@Slf4j
public class PlanExecuteAgent extends BaseAgent {

    private final List<ToolCallback> tools;
    private final AgentRegistry agentRegistry;
    private final int maxPlanSteps;
    private ChatClient planClient;
    private ChatClient executeClient;

    public PlanExecuteAgent(String name, ChatModel chatModel,
                            List<ToolCallback> tools, AgentRegistry agentRegistry,
                            int maxPlanSteps,
                            ChatConversationService conversationService,
                            ChatMessageService messageService,
                            AgentTaskManager taskManager) {
        super(name, chatModel, "plan_execute");
        this.tools = tools;
        this.agentRegistry = agentRegistry;
        this.maxPlanSteps = maxPlanSteps;
        this.conversationService = conversationService;
        this.messageService = messageService;
        this.taskManager = taskManager;
        this.usedTools = new HashSet<>();
        initClients();
    }

    private void initClients() {
        ToolCallingChatOptions toolOptions = ToolCallingChatOptions.builder()
                .toolCallbacks(tools).internalToolExecutionEnabled(false).build();
        // planClient: 不带 tools，仅用于生成计划
        this.planClient = ChatClient.builder(chatModel).build();
        // executeClient: 带 tools，用于执行步骤
        this.executeClient = ChatClient.builder(chatModel)
                .defaultOptions(toolOptions).defaultToolCallbacks(tools).build();
    }

    @Override
    public Flux<String> execute(String conversationId, String question) {
        final String convId = StringUtils.isBlank(conversationId)
                ? UUID.randomUUID().toString() : conversationId;
        this.currentConversationId = convId;
        this.currentQuestion = question;

        Flux<String> checkResult = checkRunningTask(convId);
        if (checkResult != null) return checkResult;

        initTimers();
        clearUsedTools();

        Sinks.Many<String> sink = Sinks.many().unicast().onBackpressureBuffer();

        AgentTaskManager.TaskInfo taskInfo = registerTask(convId, sink);
        if (taskInfo == null && convId != null && taskManager != null) {
            return Flux.error(new IllegalStateException("该会话正在执行中，请稍后再试"));
        }

        // 保存用户消息
        if (conversationService != null && messageService != null) {
            String title = question.length() > 32 ? question.substring(0, 32) : question;
            ChatConversation conversation = conversationService.getOrCreateBySessionId(conversationId, title);
            currentConversationNumericId = conversation.getId();
            ChatMessage userMsg = messageService.saveUserMessage(conversation.getId(), question, null, null);
            currentUserMessageId = userMsg.getId();
        }

        // 在 boundedElastic 上异步执行计划流程
        Schedulers.boundedElastic().schedule(() -> {
            try {
                runPlanExecuteLoop(conversationId, question, sink);
            } catch (Exception e) {
                log.error("PlanExecuteAgent 执行异常", e);
                sink.tryEmitNext(createErrorResponse("PlanExecuteAgent 执行异常：" + e.getMessage()));
                sink.tryEmitNext(createDoneResponse(conversationId));
                sink.tryEmitComplete();
            }
        });

        return sink.asFlux()
                .doOnCancel(() -> {
                    if (taskManager != null) taskManager.stopTask(conversationId);
                })
                .doFinally(sig -> {
                    if (taskManager != null) taskManager.stopTask(conversationId);
                });
    }

    // ======================== 核心流程 ========================

    private void runPlanExecuteLoop(String conversationId, String question, Sinks.Many<String> sink) {
        // Phase 1: 生成计划
        List<PlanStep> plan = generatePlan(question, sink);
        if (plan.isEmpty()) {
            sink.tryEmitNext(createErrorResponse("无法为当前问题生成执行计划"));
            sink.tryEmitNext(createDoneResponse(conversationId));
            sink.tryEmitComplete();
            return;
        }

        // Phase 2: 执行步骤（按 order 分组，同组并行）
        Map<Integer, List<PlanStep>> groups = new LinkedHashMap<>();
        for (PlanStep step : plan) {
            groups.computeIfAbsent(step.getOrder(), k -> new ArrayList<>()).add(step);
        }

        List<Integer> sortedOrders = groups.keySet().stream().sorted().toList();
        Map<String, String> stepResults = new LinkedHashMap<>();
        StringBuilder allResults = new StringBuilder();

        for (int order : sortedOrders) {
            List<PlanStep> batch = groups.get(order);
            if (batch.size() == 1) {
                // 单个步骤：在当前线程执行
                PlanStep step = batch.get(0);
                String result = executeStep(step, allResults.toString(), sink);
                stepResults.put(step.getId(), result);
                allResults.append("【").append(step.getTitle()).append("】\n").append(result).append("\n\n");
            } else {
                // 并行步骤
                Map<String, String> parallelResults = executeParallelSteps(batch, allResults.toString(), sink);
                stepResults.putAll(parallelResults);
                for (PlanStep step : batch) {
                    String r = parallelResults.getOrDefault(step.getId(), "[未完成]");
                    allResults.append("【").append(step.getTitle()).append("】\n").append(r).append("\n\n");
                }
            }
        }

        // Phase 3: 总结
        summarizeAndEmit(question, allResults.toString(), sink, conversationId);
    }

    // ======================== Phase 1: PLAN ========================

    private List<PlanStep> generatePlan(String question, Sinks.Many<String> sink) {
        String prompt = buildPlanUserPrompt(question);
        try {
            String response = planClient.prompt()
                    .system(PlanExecutePrompts.PLAN)
                    .user(prompt)
                    .call()
                    .content();

            List<PlanStep> plan = parsePlanResponse(response);
            if (plan.isEmpty()) return plan;

            // 限制步骤数
            if (plan.size() > maxPlanSteps) {
                plan = plan.subList(0, maxPlanSteps);
            }

            // 发射 plan SSE 事件
            String planJson = JSON.toJSONString(plan);
            sink.tryEmitNext(AgentResponse.plan(planJson));
            log.info("计划生成完成: {} 个步骤", plan.size());
            return plan;
        } catch (Exception e) {
            log.error("生成计划失败", e);
            return List.of();
        }
    }

    private String buildPlanUserPrompt(String question) {
        return String.format("""
                用户问题：%s

                当前时间：%s

                请为上述问题生成一个分步执行计划。
                """, question, LocalDateTime.now().toString());
    }

    private List<PlanStep> parsePlanResponse(String response) {
        if (response == null || response.isBlank()) return List.of();
        try {
            // 去除可能的 markdown 代码块标记
            String json = response.trim();
            if (json.startsWith("```")) {
                int start = json.indexOf('[');
                int end = json.lastIndexOf(']');
                if (start >= 0 && end > start) {
                    json = json.substring(start, end + 1);
                }
            }
            return JSON.parseArray(json, PlanStep.class);
        } catch (Exception e) {
            log.warn("解析计划 JSON 失败: {}", response);
            return List.of();
        }
    }

    // ======================== Phase 2: EXECUTE ========================

    private String executeStep(PlanStep step, String priorResults, Sinks.Many<String> sink) {
        emitStepStart(step, sink);

        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(PlanExecutePrompts.EXECUTE));
        if (priorResults != null && !priorResults.isEmpty()) {
            messages.add(new SystemMessage("前置步骤已完成的成果：\n" + priorResults));
        }
        messages.add(new UserMessage("【Current Task】\n" + step.getInstruction()));

        StringBuilder result = new StringBuilder();
        try {
            String response = callWithToolLoop(messages, sink, result);
            emitStepDone(step, response, sink);
            return response;
        } catch (Exception e) {
            log.error("步骤 {} 执行失败: {}", step.getId(), e.getMessage());
            emitStepError(step, e.getMessage(), sink);
            return "[执行失败: " + e.getMessage() + "]";
        }
    }

    private Map<String, String> executeParallelSteps(List<PlanStep> batch, String priorResults,
                                                     Sinks.Many<String> sink) {
        Map<String, String> results = new ConcurrentHashMap<>();
        AtomicInteger remaining = new AtomicInteger(batch.size());

        for (PlanStep step : batch) {
            Schedulers.boundedElastic().schedule(() -> {
                try {
                    String result = executeStep(step, priorResults, sink);
                    results.put(step.getId(), result);
                } catch (Exception e) {
                    results.put(step.getId(), "[错误: " + e.getMessage() + "]");
                } finally {
                    remaining.decrementAndGet();
                }
            });
        }

        // 等待所有并行步骤完成
        while (remaining.get() > 0) {
            try { Thread.sleep(100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
        }
        return results;
    }

    /**
     * 带工具调用循环的 LLM 调用：最多 maxRounds 轮，支持自动 tool calling。
     */
    private String callWithToolLoop(List<Message> messages, Sinks.Many<String> sink, StringBuilder textAccumulator) {
        int rounds = 0;
        int maxRounds = 10;

        while (rounds < maxRounds) {
            rounds++;
            var chatResponse = executeClient.prompt().messages(messages)
                    .options(OpenAiChatOptions.builder().internalToolExecutionEnabled(false).build())
                    .call()
                    .chatClientResponse();

            var response = chatResponse.chatResponse();
            String aiText = response.getResult().getOutput().getText();
            List<AssistantMessage.ToolCall> toolCalls = response.getResult().getOutput().getToolCalls();

            // 如果 LLM 返回了文本内容，流式输出
            if (StringUtils.isNotBlank(aiText)) {
                sink.tryEmitNext(createTextResponse(aiText));
                textAccumulator.append(aiText);
            }

            // 没有工具调用 → 完成
            if (toolCalls == null || toolCalls.isEmpty()) {
                return textAccumulator.toString();
            }

            // 有工具调用 → 添加 assistant message，执行工具
            messages.add(AssistantMessage.builder().toolCalls(toolCalls).build());

            List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>();
            for (AssistantMessage.ToolCall tc : toolCalls) {
                String toolName = tc.name();
                String argsJson = tc.arguments();
                recordUsedTool(toolName);

                // 发射工具状态
                JSONObject toolMsg = new JSONObject();
                toolMsg.put("tool", toolName);
                toolMsg.put("status", "start");
                toolMsg.put("args", argsJson);
                sink.tryEmitNext(createToolResponse(toolMsg.toJSONString()));

                // 如果是 Agent 工具 → 设置 SubAgentContext
                boolean isAgentTool = agentRegistry != null && agentRegistry.getAgentNames().contains(toolName);
                if (isAgentTool) {
                    setupSubAgentContext(sink);
                }

                try {
                    ToolCallback callback = findTool(toolName);
                    String resultStr;
                    if (callback != null) {
                        resultStr = callback.call(argsJson);
                    } else {
                        resultStr = "{ \"error\": \"工具未找到：" + toolName + "\" }";
                    }
                    responses.add(new ToolResponseMessage.ToolResponse(tc.id(), toolName, resultStr));

                    JSONObject doneMsg = new JSONObject();
                    doneMsg.put("tool", toolName);
                    doneMsg.put("status", "done");
                    doneMsg.put("result",
                            resultStr.length() > 300 ? resultStr.substring(0, 300) + "..." : resultStr);
                    sink.tryEmitNext(createToolResponse(doneMsg.toJSONString()));
                } catch (Exception e) {
                    responses.add(new ToolResponseMessage.ToolResponse(tc.id(), toolName,
                            "{ \"error\": \"" + e.getMessage() + "\" }"));
                } finally {
                    if (isAgentTool) {
                        SubAgentContext.clear();
                    }
                }
            }

            messages.add(ToolResponseMessage.builder().responses(responses).build());
        }

        return textAccumulator.toString();
    }

    // ======================== Phase 3: SUMMARIZE ========================

    private void summarizeAndEmit(String question, String allResults,
                                  Sinks.Many<String> sink, String conversationId) {
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(PlanExecutePrompts.SUMMARIZE));
        messages.add(new UserMessage(String.format("""
                用户问题：%s

                所有步骤的研究结果：
                %s

                请基于以上研究结果，生成最终分析报告。
                """, question, allResults)));

        StringBuilder finalAnswer = new StringBuilder();
        try {
            executeClient.prompt().messages(messages)
                    .options(OpenAiChatOptions.builder().internalToolExecutionEnabled(false).build())
                    .stream()
                    .chatResponse()
                    .publishOn(Schedulers.boundedElastic())
                    .doOnNext(chunk -> {
                        if (chunk.getResult() != null && chunk.getResult().getOutput() != null) {
                            String text = chunk.getResult().getOutput().getText();
                            if (text != null) {
                                sink.tryEmitNext(createTextResponse(text));
                                finalAnswer.append(text);
                            }
                        }
                    })
                    .doOnComplete(() -> {
                        // 保存最终结果
                        saveFinalResult(conversationId, finalAnswer.toString());
                        sink.tryEmitNext(createDoneResponse(conversationId));
                        sink.tryEmitComplete();
                    })
                    .doOnError(err -> {
                        log.error("总结阶段异常", err);
                        sink.tryEmitNext(createErrorResponse("总结异常：" + err.getMessage()));
                        sink.tryEmitNext(createDoneResponse(conversationId));
                        sink.tryEmitComplete();
                    })
                    .blockLast();
        } catch (Exception e) {
            log.error("总结阶段异常", e);
            sink.tryEmitNext(createErrorResponse("总结异常：" + e.getMessage()));
            sink.tryEmitNext(createDoneResponse(conversationId));
            sink.tryEmitComplete();
        }
    }

    // ======================== 辅助方法 ========================

    private void emitStepStart(PlanStep step, Sinks.Many<String> sink) {
        JSONObject msg = new JSONObject();
        msg.put("stepId", step.getId());
        msg.put("title", step.getTitle());
        msg.put("instruction", step.getInstruction());
        msg.put("order", step.getOrder());
        sink.tryEmitNext(AgentResponse.planStepStart(msg.toJSONString()));
    }

    private void emitStepDone(PlanStep step, String result, Sinks.Many<String> sink) {
        JSONObject msg = new JSONObject();
        msg.put("stepId", step.getId());
        msg.put("title", step.getTitle());
        msg.put("result", result.length() > 500 ? result.substring(0, 500) + "..." : result);
        sink.tryEmitNext(AgentResponse.planStepDone(msg.toJSONString()));
    }

    private void emitStepError(PlanStep step, String error, Sinks.Many<String> sink) {
        JSONObject msg = new JSONObject();
        msg.put("stepId", step.getId());
        msg.put("title", step.getTitle());
        msg.put("error", error);
        sink.tryEmitNext(AgentResponse.planStepError(msg.toJSONString()));
    }

    private void setupSubAgentContext(Sinks.Many<String> sink) {
        SubAgentContext ctx = new SubAgentContext(
                currentConversationId != null ? currentConversationId : "",
                sink,
                currentConversationNumericId,
                currentUserMessageId,
                conversationService,
                messageService,
                null);
        SubAgentContext.set(ctx);
    }

    private ToolCallback findTool(String name) {
        return tools.stream().filter(t -> t.getToolDefinition().name().equals(name)).findFirst().orElse(null);
    }

    private void saveFinalResult(String conversationId, String finalAnswer) {
        if (conversationService != null && messageService != null
                && currentConversationNumericId != null && currentUserMessageId != null
                && !finalAnswer.isEmpty()) {
            long totalResponseTime = getTotalResponseTime();
            messageService.saveAssistantMessage(
                    currentConversationNumericId, currentUserMessageId,
                    finalAnswer, "",
                    getUsedToolsString(), "", null, null,
                    null, null, null);
            String lastMsg = finalAnswer.length() > 64 ? finalAnswer.substring(0, 64) : finalAnswer;
            conversationService.updateLastMessage(currentConversationNumericId, lastMsg);
        }
    }

    // ======================== Builder ========================

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String name = "plan-execute-agent";
        private ChatModel chatModel;
        private List<ToolCallback> tools = List.of();
        private AgentRegistry agentRegistry;
        private int maxPlanSteps = 8;
        private ChatConversationService conversationService;
        private ChatMessageService messageService;
        private AgentTaskManager taskManager;

        public Builder name(String name) { this.name = name; return this; }
        public Builder chatModel(ChatModel chatModel) { this.chatModel = chatModel; return this; }
        public Builder tools(ToolCallback... tools) { this.tools = Arrays.asList(tools); return this; }
        public Builder tools(List<ToolCallback> tools) { this.tools = tools; return this; }
        public Builder agentRegistry(AgentRegistry agentRegistry) { this.agentRegistry = agentRegistry; return this; }
        public Builder maxPlanSteps(int maxPlanSteps) { this.maxPlanSteps = maxPlanSteps; return this; }
        public Builder conversationService(ChatConversationService s) { this.conversationService = s; return this; }
        public Builder messageService(ChatMessageService s) { this.messageService = s; return this; }
        public Builder taskManager(AgentTaskManager s) { this.taskManager = s; return this; }

        public PlanExecuteAgent build() {
            if (chatModel == null) throw new IllegalArgumentException("chatModel 不能为空！");
            return new PlanExecuteAgent(name, chatModel, tools, agentRegistry, maxPlanSteps,
                    conversationService, messageService, taskManager);
        }
    }
}
