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
public class KnowledgeFileResp implements Serializable {

    private Long id;

    private Long baseId;

    private Long parentId;

    private String ancestors;

    private String name;

    private String fileType;

    private String mineType;

    private String content;

    private String storagePath;

    private Long fileSize;

    private String fileUrl;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
