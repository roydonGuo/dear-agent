package com.roydon.dear.event.events;

import com.roydon.dear.event.AgentEvent;
import com.roydon.dear.event.AgentPhase;

public class PlanStepErrorEvent extends AgentEvent {
    private final String stepId;
    private final String title;
    private final String error;

    public PlanStepErrorEvent(String stepId, String title, String error) {
        super("plan_step_error", AgentPhase.PLANNING);
        this.stepId = stepId;
        this.title = title;
        this.error = error;
    }

    public String getStepId() { return stepId; }
    public String getTitle() { return title; }
    public String getError() { return error; }
}
