package com.roydon.dear.session.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@TableName("ai_chat_conversation")
public class ChatConversation implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String sessionId;

    private Long userId;

    private Long modelId;

    private String title;

    private String pinned;

    private LocalDateTime pinnedTime;

    private String systemMessage;

    /**
     * 人设id，关联 AiPrompt 的id
     */
    private Long promptId;

    private Double temperature;

    private Integer maxTokens;

    private Integer maxContexts;

    private String lastMessage;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private String delFlag;
}
