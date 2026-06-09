package com.roydon.dear.event.events;

import com.roydon.dear.event.AgentEvent;
import com.roydon.dear.event.AgentPhase;

public class AgentDoneEvent extends AgentEvent {
    private final String agentId;
    private final String result;

    public AgentDoneEvent(String agentId, String result) {
        super("agent_done", AgentPhase.COLLABORATING);
        this.agentId = agentId;
        this.result = result;
    }

    public String getAgentId() { return agentId; }
    public String getResult() { return result; }
}
