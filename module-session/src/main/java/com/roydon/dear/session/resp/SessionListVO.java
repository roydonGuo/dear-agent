package com.roydon.dear.session.resp;

import com.roydon.dear.session.entity.AiSession;
import com.roydon.dear.session.entity.ChatConversation;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class SessionListVO {
    private String conversationId;
    private String agentType;
    private String question;
    private String answer;
//    private Integer messageCount;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    private String fileid;
    private String thinking;
    // 人设相关
    private String avatar;
    private String systemPrompt;

    public static SessionListVO fromAiSession(AiSession session, Integer messageCount) {
        return SessionListVO.builder()
                .conversationId(session.getSessionId())
                .agentType(session.getAgentType())
                .question(session.getQuestion())
                .answer(session.getAnswer())
                .createTime(session.getCreateTime())
                .updateTime(session.getUpdateTime())
                .fileid(session.getFileid())
                .thinking(session.getThinking())
                .build();
    }

    public static SessionListVO fromConversation(ChatConversation conversation) {
        return SessionListVO.builder()
                .conversationId(conversation.getSessionId())
                .question(conversation.getTitle())
                .answer(conversation.getLastMessage())
                .createTime(conversation.getCreateTime())
                .updateTime(conversation.getUpdateTime())
                .build();
    }
}
