package com.roydon.dear.knowledge.domain.resp;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeFileTreeNode implements Serializable {

    private Long id;

    private String name;

    private String type;

    private String fileType;

    private String content;

    private String storagePath;

    private Long fileSize;

    private String fileUrl;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

//    @Builder.Default
//    private boolean expand = false;

    @Builder.Default
    private List<KnowledgeFileTreeNode> children = new ArrayList<>();
}
