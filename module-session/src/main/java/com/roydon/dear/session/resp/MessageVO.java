package com.roydon.dear.session.resp;

import com.roydon.dear.session.entity.AiChatFile;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MessageVO {
    private Long id;
    private String question;
    private String answer;
    /** 旧协议字段，逐步废弃 */
    @Deprecated
    private String thinking;
    @Deprecated
    private String tools;
    @Deprecated
    private String reference;
    @Deprecated
    private String recommend;
    @Deprecated
    private String knowledge;
    /** 新协议：完整事件流 JSON 数组，与实时 SSE 事件结构一致 */
    private Object eventStream;
    private LocalDateTime createTime;
    private String fileid;
    private List<AiChatFile> chatFileList;
}
