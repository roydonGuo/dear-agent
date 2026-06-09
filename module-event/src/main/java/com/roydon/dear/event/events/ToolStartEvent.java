package com.roydon.dear.event.events;

import com.roydon.dear.event.AgentEvent;
import com.roydon.dear.event.AgentPhase;

import java.util.Map;

public class ToolStartEvent extends AgentEvent {
    private final String id;
    private final String name;
    private final Map<String, Object> input;

    public ToolStartEvent(String id, String name, Map<String, Object> input) {
        super("tool_start", AgentPhase.EXECUTING);
        this.id = id;
        this.name = name;
        this.input = input;
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public Map<String, Object> getInput() { return input; }
}
