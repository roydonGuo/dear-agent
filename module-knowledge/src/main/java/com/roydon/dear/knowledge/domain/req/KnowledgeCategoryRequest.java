package com.roydon.dear.knowledge.domain.req;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeCategoryRequest {

    private String name;

    private String icon;

    private Integer sort;
}
