package com.roydon.dear.session.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("ai_session")
public class AiSession {

    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    @TableField("session_id")
    private String sessionId;

    @TableField("agent_type")
    private String agentType;

    @TableField("question")
    private String question;

    @TableField("answer")
    private String answer;

    @TableField("tools")
    private String tools;

    @TableField("reference")
    private String reference;

    @TableField("first_response_time")
    private Long firstResponseTime;

    @TableField("total_response_time")
    private Long totalResponseTime;

    @TableField("create_time")
    private LocalDateTime createTime;

    @TableField("update_time")
    private LocalDateTime updateTime;

    @TableField("thinking")
    private String thinking;

    @TableField("fileid")
    private String fileid;

    @TableField("recommend")
    private String recommend;
}
