package com.roydon.dear.event.events;

import com.roydon.dear.event.AgentEvent;
import com.roydon.dear.event.AgentPhase;

import java.util.List;

public class DoneEvent extends AgentEvent {
    private final String conversationId;
    private final long totalDurationMs;
    private final int roundCount;
    private final List<String> usedTools;

    public DoneEvent(String conversationId, long totalDurationMs, int roundCount, List<String> usedTools) {
        super("done", AgentPhase.COMPLETED);
        this.conversationId = conversationId;
        this.totalDurationMs = totalDurationMs;
        this.roundCount = roundCount;
        this.usedTools = usedTools;
    }

    public String getConversationId() { return conversationId; }
    public long getTotalDurationMs() { return totalDurationMs; }
    public int getRoundCount() { return roundCount; }
    public List<String> getUsedTools() { return usedTools; }
}
