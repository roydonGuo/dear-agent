package com.roydon.dear.event.events;

import com.roydon.dear.event.AgentEvent;
import com.roydon.dear.event.AgentPhase;

public class ErrorEvent extends AgentEvent {
    private final String conversationId;
    private final String message;
    private final String code;

    public ErrorEvent(String conversationId, String message, String code) {
        super("error", AgentPhase.ERROR);
        this.conversationId = conversationId;
        this.message = message;
        this.code = code;
    }

    public String getConversationId() { return conversationId; }
    public String getMessage() { return message; }
    public String getCode() { return code; }
}
