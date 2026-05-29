package com.roydon.dear.session.resp;

import com.roydon.dear.session.entity.AiChatFile;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

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
    private String knowledge;
    private List<AiChatFile> chatFileList;
}
