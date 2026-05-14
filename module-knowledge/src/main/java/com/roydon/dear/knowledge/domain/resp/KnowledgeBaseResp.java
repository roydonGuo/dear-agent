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
public class KnowledgeBaseResp implements Serializable {

    private Long id;

    private String name;

    private String description;

    private String coverPath;

    private String categoryIds;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
