package com.roydon.dear.event.events;

import com.roydon.dear.event.AgentEvent;
import com.roydon.dear.event.AgentPhase;

public class ToolErrorEvent extends AgentEvent {
    private final String id;
    private final String name;
    private final String error;

    public ToolErrorEvent(String id, String name, String error) {
        super("tool_error", AgentPhase.EXECUTING);
        this.id = id;
        this.name = name;
        this.error = error;
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public String getError() { return error; }
}
