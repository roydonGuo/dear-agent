package com.roydon.dear.session.resp;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class MessageVO {
    private Long id;
    private String question;
    private String answer;
    private String thinking;
    private String tools;
    private String reference;
    private LocalDateTime createTime;
    private String fileid;
    private String recommend;
}
