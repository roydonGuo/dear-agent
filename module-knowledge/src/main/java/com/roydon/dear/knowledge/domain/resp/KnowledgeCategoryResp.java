package com.roydon.dear.knowledge.domain.resp;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeCategoryResp implements Serializable {

    private Long id;

    private String name;

    private String icon;

    private Integer sort;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
