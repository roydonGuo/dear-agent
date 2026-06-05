package com.roydon.dear.agent.orchestrator;

import com.roydon.dear.session.service.ChatConversationService;
import com.roydon.dear.session.service.ChatMessageService;
import reactor.core.publisher.Sinks;

/**
 * 子 Agent 执行上下文，通过 ThreadLocal 从编排器传递到子 Agent。
 * 子 Agent 检测到此上下文时，走流式输出 + 父会话持久化路径。
 */
public class SubAgentContext {

    private static final ThreadLocal<SubAgentContext> CURRENT = new ThreadLocal<>();

    private final String parentConversationId;
    private final Sinks.Many<String> sink;
    private final Long parentConversationNumericId;
    private final Long parentUserMessageId;
    private final ChatConversationService conversationService;
    private final ChatMessageService messageService;
    private final String fileIds;

    public SubAgentContext(String parentConversationId,
                           Sinks.Many<String> sink,
                           Long parentConversationNumericId,
                           Long parentUserMessageId,
                           ChatConversationService conversationService,
                           ChatMessageService messageService,
                           String fileIds) {
        this.parentConversationId = parentConversationId;
        this.sink = sink;
        this.parentConversationNumericId = parentConversationNumericId;
        this.parentUserMessageId = parentUserMessageId;
        this.conversationService = conversationService;
        this.messageService = messageService;
        this.fileIds = fileIds;
    }

    public String getParentConversationId() { return parentConversationId; }
    public Sinks.Many<String> getSink() { return sink; }
    public Long getParentConversationNumericId() { return parentConversationNumericId; }
    public Long getParentUserMessageId() { return parentUserMessageId; }
    public ChatConversationService getConversationService() { return conversationService; }
    public ChatMessageService getMessageService() { return messageService; }
    public String getFileIds() { return fileIds; }

    public static void set(SubAgentContext ctx) { CURRENT.set(ctx); }
    public static SubAgentContext get() { return CURRENT.get(); }
    public static void clear() { CURRENT.remove(); }
}
