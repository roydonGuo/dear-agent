package com.roydon.dear.event.events;

import com.roydon.dear.event.AgentEvent;
import com.roydon.dear.event.AgentPhase;

import java.util.List;

public class RecommendEvent extends AgentEvent {
    private final List<String> questions;

    public RecommendEvent(List<String> questions) {
        super("recommend", AgentPhase.RESPONDING);
        this.questions = questions;
    }

    public List<String> getQuestions() { return questions; }
}
