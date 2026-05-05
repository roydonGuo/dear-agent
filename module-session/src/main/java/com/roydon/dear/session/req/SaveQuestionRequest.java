package com.roydon.dear.session.req;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SaveQuestionRequest {
    private String sessionId;
    private String question;
    private String fileid;
    private String tools;
    private Long firstResponseTime;
}
