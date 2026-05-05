package com.roydon.dear.session.resp;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class SessionDetailVO {
    private String conversationId;
    private String agentType;
    private List<MessageVO> messages;
    private String fileid;
}
