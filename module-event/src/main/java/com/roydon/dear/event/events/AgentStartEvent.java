package com.roydon.dear.event.events;

import com.roydon.dear.event.AgentEvent;
import com.roydon.dear.event.AgentPhase;

public class AgentStartEvent extends AgentEvent {
    private final String agentId;
    private final String task;

    public AgentStartEvent(String agentId, String task) {
        super("agent_start", AgentPhase.COLLABORATING);
        this.agentId = agentId;
        this.task = task;
    }

    public String getAgentId() { return agentId; }
    public String getTask() { return task; }
}
