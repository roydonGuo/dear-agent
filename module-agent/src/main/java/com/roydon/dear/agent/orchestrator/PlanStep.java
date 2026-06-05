package com.roydon.dear.agent.orchestrator;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PlanStep {
    private String id;
    private String title;
    private String instruction;
    private int order;
}
