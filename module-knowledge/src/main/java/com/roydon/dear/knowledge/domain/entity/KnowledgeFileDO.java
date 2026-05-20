package com.roydon.dear.knowledge.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.roydon.dear.knowledge.enums.KnowledgeFileStatus;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@TableName("ai_knowledge_file")
public class KnowledgeFileDO implements Serializable {

    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    @TableField("base_id")
    private Long baseId;

    @TableField("parent_id")
    private Long parentId;

    @TableField("ancestors")
    private String ancestors;

    @TableField("name")
    private String name;

    @TableField("file_type")
    private String fileType;

    @TableField("mine_type")
    private String mineType;

    @TableField("content")
    private String content;

    @TableField("storage_path")
    private String storagePath;

    @TableField("file_size")
    private Long fileSize;

    @TableField("create_time")
    private LocalDateTime createTime;

    @TableField("update_time")
    private LocalDateTime updateTime;

    /**
     * 状态
     * {@see KnowledgeFileStatus}
     */
    @TableField("status")
    private KnowledgeFileStatus status;

    @TableField("processed_storage_path")
    private String processedStoragePath;

}
