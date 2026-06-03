package com.roydon.dear.session.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@TableName("ai_chat_message")
public class ChatMessage implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long conversationId;

    private Long replyId;

    private String messageType;

    private String content;

    private String useContext;

    private String delFlag;

    private String tools;

    private String thinking;

    private String reference;

    private String recommend;

    private String knowledge;

    private String fileid;

    private String fileIds;

    private Long firstResponseTime;

    private Long totalResponseTime;

    private LocalDateTime createTime;
}
