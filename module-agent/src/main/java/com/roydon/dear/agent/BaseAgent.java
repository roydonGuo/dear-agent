package com.roydon.dear.agent;

import com.alibaba.fastjson2.JSON;
import com.roydon.dear.common.AgentResponse;
import com.roydon.dear.common.manager.AgentTaskManager;
import com.roydon.dear.common.prompts.ReactAgentPrompts;
import com.roydon.dear.session.entity.AiSession;
import com.roydon.dear.session.req.UpdateAnswerRequest;
import com.roydon.dear.session.service.AiSessionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.util.StopWatch;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Slf4j
public abstract class BaseAgent {

    protected final ChatModel chatModel;
    protected final String name;
    protected ChatMemory chatMemory;
    protected AiSessionService sessionService;
    protected AgentTaskManager taskManager;
    protected String agentType;

    protected boolean enableRecommendations = false;

    protected long startTime;
    protected long firstResponseTime;
    protected Set<String> usedTools;
    protected Long currentSessionId;
    protected String currentConversationId;
    protected String currentQuestion;
    protected String currentRecommendations;

    public BaseAgent(String name, ChatModel chatModel, String agentType) {
        this.name = name;
        this.chatModel = chatModel;
        this.agentType = agentType;
    }

    public abstract Flux<String> execute(String conversationId, String question);

    // ===== 通用方法 =====

    protected void loadChatHistory(String conversationId, List<Message> messages, boolean skipSystem, boolean addLabel) {
        if (conversationId != null && chatMemory != null) {
            List<Message> history = chatMemory.get(conversationId);
            if (history != null && !history.isEmpty()) {
                if (addLabel) messages.add(new UserMessage("对话历史："));
                for (Message msg : history) {
                    if (skipSystem && msg instanceof SystemMessage) continue;
                    messages.add(msg);
                }
            }
        }
    }

    protected List<Message> getChatHistory(String conversationId) {
        if (conversationId != null && chatMemory != null) {
            return chatMemory.get(conversationId);
        }
        return null;
    }

    public ChatMemory createPersistentChatMemory(String sessionId, int maxMessages) {
        if (sessionService == null) {
            log.warn("sessionService is null, cannot load chat memory");
            return MessageWindowChatMemory.builder().maxMessages(maxMessages).build();
        }
        List<AiSession> history = sessionService.findRecentBySessionId(sessionId, maxMessages);
        ChatMemory chatMemory = MessageWindowChatMemory.builder().maxMessages(maxMessages).build();
        if (history != null && !history.isEmpty()) {
            for (int i = history.size() - 1; i >= 0; i--) {
                AiSession record = history.get(i);
                if (record.getQuestion() != null) chatMemory.add(sessionId, new UserMessage(record.getQuestion()));
                if (record.getAnswer() != null) chatMemory.add(sessionId, new AssistantMessage(record.getAnswer()));
            }
            log.debug("加载会话历史: sessionId={}, recordCount={}", sessionId, history.size());
        }
        return chatMemory;
    }

    protected String createResponse(String content, String type) { return AgentResponse.json(type, content); }
    protected String createTextResponse(String content) { return AgentResponse.text(content); }
    protected String createThinkingResponse(String content) { return AgentResponse.thinking(content); }
    protected String createReferenceResponse(String content) { return AgentResponse.reference(content); }
    protected String createErrorResponse(String content) { return AgentResponse.error(content); }
    protected String createRecommendResponse(String content) { return AgentResponse.recommend(content); }
    protected String createDoneResponse(String content) { return AgentResponse.done(content); }

    protected void recordFirstResponse() {
        if (firstResponseTime == 0 && startTime > 0) {
            firstResponseTime = System.currentTimeMillis() - startTime;
            log.debug("记录首次响应时间: {}ms", firstResponseTime);
        }
    }

    protected Flux<String> checkRunningTask(String conversationId) {
        if (conversationId != null && taskManager != null && taskManager.hasRunningTask(conversationId)) {
            return Flux.error(new IllegalStateException("该会话正在执行中，请稍后再试"));
        }
        return null;
    }

    protected AgentTaskManager.TaskInfo registerTask(String conversationId, Sinks.Many<String> sink) {
        if (conversationId != null && taskManager != null) {
            AgentTaskManager.TaskInfo taskInfo = taskManager.registerTask(conversationId, sink, agentType);
            if (taskInfo == null) log.warn("任务注册失败: conversationId={}", conversationId);
            return taskInfo;
        }
        return null;
    }

    protected void initTimers() { startTime = System.currentTimeMillis(); firstResponseTime = 0; }

    protected long getTotalResponseTime() { return startTime == 0 ? 0 : System.currentTimeMillis() - startTime; }

    protected String getUsedToolsString() {
        return usedTools == null || usedTools.isEmpty() ? "" : String.join(",", usedTools);
    }

    protected void clearUsedTools() { if (usedTools != null) usedTools.clear(); }

    protected void recordUsedTool(String toolName) { if (usedTools != null && toolName != null) usedTools.add(toolName); }

    protected String generateRecommendations(String conversationId, String currentQuestion, String currentAnswer) {
        if (!enableRecommendations) return null;
        try {
            StopWatch sw = new StopWatch();
            sw.start();
            List<Message> messages = new ArrayList<>();
            messages.add(new SystemMessage(ReactAgentPrompts.getRecommendPrompt()));
            loadChatHistory(conversationId, messages, true, true);
            messages.add(new UserMessage("当前会话："));
            messages.add(new UserMessage(currentQuestion));
            if (currentAnswer != null) messages.add(new AssistantMessage(currentAnswer));

            BeanOutputConverter<List<String>> converter = new BeanOutputConverter<>(new ParameterizedTypeReference<>() {});
            messages.add(new UserMessage("请根据上述对话生成3个推荐问题。输出格式为：\n" + converter.getFormat()));

            String response = ChatClient.builder(chatModel).build().prompt().messages(messages).call().content();
            if (response != null && !response.isEmpty()) {
                List<String> recommendations = converter.convert(response);
                if (recommendations != null && !recommendations.isEmpty()) {
                    String jsonStr = JSON.toJSONString(recommendations);
                    sw.stop();
                    log.info("生成推荐问题成功: {}，耗时：{}ms", jsonStr, sw.getTotalTimeMillis());
                    return jsonStr;
                }
            }
            log.warn("生成推荐问题失败，响应格式无效: {}", response);
            return null;
        } catch (Exception e) {
            log.error("生成推荐问题异常", e);
            return null;
        }
    }

    protected boolean updateAnswer(UpdateAnswerRequest request) {
        if (sessionService != null) {
            boolean result = sessionService.updateAnswer(request);
            if (result) log.debug("保存会话结果: sessionId={}, answerLength={}", request.getId(), request.getAnswer().length());
            return result;
        }
        return false;
    }

    public void setChatMemory(ChatMemory chatMemory) { this.chatMemory = chatMemory; }
    public void setSessionService(AiSessionService sessionService) { this.sessionService = sessionService; }
    public void setTaskManager(AgentTaskManager taskManager) { this.taskManager = taskManager; }
    public Long getCurrentSessionId() { return currentSessionId; }
    public String getCurrentConversationId() { return currentConversationId; }
    public String getAgentType() { return agentType; }
    public void setEnableRecommendations(boolean enableRecommendations) { this.enableRecommendations = enableRecommendations; }
    public boolean isEnableRecommendations() { return enableRecommendations; }
}
