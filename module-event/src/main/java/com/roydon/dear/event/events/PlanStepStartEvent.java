package com.roydon.dear.event.events;

import com.roydon.dear.event.AgentEvent;
import com.roydon.dear.event.AgentPhase;

public class PlanStepStartEvent extends AgentEvent {
    private final String stepId;
    private final String title;
    private final String instruction;
    private final int order;

    public PlanStepStartEvent(String stepId, String title, String instruction, int order) {
        super("plan_step_start", AgentPhase.PLANNING);
        this.stepId = stepId;
        this.title = title;
        this.instruction = instruction;
        this.order = order;
    }

    public String getStepId() { return stepId; }
    public String getTitle() { return title; }
    public String getInstruction() { return instruction; }
    public int getOrder() { return order; }
}
