package com.roydon.dear.event.events;

import com.roydon.dear.event.AgentEvent;
import com.roydon.dear.event.AgentPhase;

public class PlanStepEndEvent extends AgentEvent {
    private final String stepId;
    private final String title;
    private final String result;

    public PlanStepEndEvent(String stepId, String title, String result) {
        super("plan_step_end", AgentPhase.PLANNING);
        this.stepId = stepId;
        this.title = title;
        this.result = result;
    }

    public String getStepId() { return stepId; }
    public String getTitle() { return title; }
    public String getResult() { return result; }
}
