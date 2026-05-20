package com.roydon.dear.session.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Builder;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;
import java.io.Serializable;

/**
 * chat 文件表
 *
 * @author roydon
 * @since 2026-05-20 21:41:50
 */
@Builder
@Accessors(chain = true)
@Data
@TableName("ai_chat_file")
public class AiChatFile implements Serializable {
    private static final long serialVersionUID = -37996109587622790L;
    /**
     * 主键ID
     */
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;
    /**
     * 原始文件名
     */
    private String fileName;
    /**
     * 文件类型（png/jpg/md/pdf/doc/docx等）
     */
    private String fileType;
    /**
     * 文件大小（字节）
     */
    private Long fileSize;
    /**
     * MinIO中的存储url
     */
    private String minioPath;
    /**
     * 解析后的纯文本内容
     */
    private String extractedText;
    /**
     * 创建时间
     */
    private LocalDateTime createdTime;
    /**
     * 更新时间
     */
    private LocalDateTime updateTime;
    /**
     * 会话ID（可选，用于关联特定会话）
     * {@see AiSession.id}
     */
    private String conversationId;
    /**
     * 文件状态：PENDING/PROCESSING/SUCCESS/FAILED
     */
    private String status;
    /**
     * 是否向量化
     */
    private Integer embed;


}

