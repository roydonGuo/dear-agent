package com.roydon.dear.prompt.req;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiPromptRequest {

    private String title;

    private String avatar;

    private String description;

    private String prompt;

    private String categoryIds;
}
