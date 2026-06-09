package com.roydon.dear.event.events;

import com.roydon.dear.event.AgentEvent;
import com.roydon.dear.event.AgentPhase;

import java.util.List;

public class PlanCreatedEvent extends AgentEvent {
    private final List<PlanStep> steps;

    public PlanCreatedEvent(List<PlanStep> steps) {
        super("plan_created", AgentPhase.PLANNING);
        this.steps = steps;
    }

    public List<PlanStep> getSteps() { return steps; }

    public record PlanStep(String id, String title, String instruction, int order) {}
}
