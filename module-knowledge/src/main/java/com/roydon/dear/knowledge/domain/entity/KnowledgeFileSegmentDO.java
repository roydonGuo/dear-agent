package com.roydon.dear.knowledge.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.roydon.dear.knowledge.enums.FileSegmentStatus;
import lombok.Data;

import java.time.LocalDateTime;
import java.io.Serializable;

/**
 * knowledge-文件片段表(KnowledgeFileSegment)实体类
 *
 * @author roydon
 * @since 2026-05-23 18:35:32
 */
@Data
@TableName("ai_knowledge_file_segment")
public class KnowledgeFileSegmentDO implements Serializable {
    private static final long serialVersionUID = 828611754617948637L;
    /**
     * 片段ID
     */
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;
    /**
     * 所属文档ID
     * {@see KnowledgeFileDO.id}
     */
    private Long fileId;
    /**
     * 文本内容
     */
    private String text;
    /**
     * 分片ID
     */
    private String chunkId;
    /**
     * 顺序
     */
    private Integer chunkOrder;
    /**
     * 元数据
     */
    private String metadata;
    /**
     * 嵌入ID
     */
    private String embeddingId;
    /**
     * 状态：CHUNKED, VECTOR_STORED
     */
    private FileSegmentStatus status;
    /**
     * 是否跳过嵌入生成
     * 0 for false, 1 for true
     */
    private Integer skipEmbedding;
    /**
     * 创建时间
     */
    private LocalDateTime createdAt;
    /**
     * 修改时间
     */
    private LocalDateTime updatedAt;


}

