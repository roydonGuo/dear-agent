package com.roydon.dear.prompt.req;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiPromptCategoryRequest {

    private String name;

    private Integer sort;

    private String icon;
}
