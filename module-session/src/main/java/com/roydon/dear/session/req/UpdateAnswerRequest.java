package com.roydon.dear.session.req;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpdateAnswerRequest {
    private Long id;
    private String answer;
    private String thinking;
    private String tools;
    private String reference;
    private Long firstResponseTime;
    private Long totalResponseTime;
    private String recommend;
}
