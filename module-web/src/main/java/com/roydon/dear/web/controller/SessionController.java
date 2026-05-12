package com.roydon.dear.web.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.roydon.dear.common.BaseResult;
import com.roydon.dear.session.entity.ChatConversation;
import com.roydon.dear.session.entity.ChatMessage;
import com.roydon.dear.session.resp.MessageVO;
import com.roydon.dear.session.resp.PageResult;
import com.roydon.dear.session.resp.SessionDetailVO;
import com.roydon.dear.session.resp.SessionListVO;
import com.roydon.dear.session.service.ChatConversationService;
import com.roydon.dear.session.service.ChatMessageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Tag(name = "会话管理", description = "会话查询、列表、删除等接口")
@RestController
@RequestMapping("/session")
@RequiredArgsConstructor
public class SessionController {

    private final ChatConversationService conversationService;
    private final ChatMessageService messageService;

    @GetMapping("/{conversationId}")
    @Operation(summary = "查询会话详情", description = "根据conversationId查询会话详情")
    public BaseResult<SessionDetailVO> getSession(@PathVariable String conversationId) {
        log.info("查询会话详情: conversationId={}", conversationId);
        try {
            ChatConversation conversation = conversationService.getBySessionId(conversationId);
            if (conversation == null) return BaseResult.newError("会话不存在");

            List<ChatMessage> messages = messageService.findByConversationId(conversation.getId());

            SessionDetailVO detailVO = SessionDetailVO.builder()
                    .conversationId(conversationId)
                    .messages(buildMessageVOs(messages))
                    .build();
            return BaseResult.newSuccess(detailVO);
        } catch (Exception e) {
            log.error("查询会话详情失败: conversationId={}", conversationId, e);
            return BaseResult.newError("查询会话详情失败: " + e.getMessage());
        }
    }

    @GetMapping("/list")
    @Operation(summary = "查询会话列表", description = "分页查询会话列表")
    public BaseResult<PageResult<SessionListVO>> getSessionList(
            @Parameter(description = "页码，默认1") @RequestParam(defaultValue = "1") Integer pageNum,
            @Parameter(description = "页大小，默认10") @RequestParam(defaultValue = "10") Integer pageSize) {
        log.info("查询会话列表: pageNum={}, pageSize={}", pageNum, pageSize);
        try {
            Page<ChatConversation> page = new Page<>(pageNum, pageSize);
            IPage<ChatConversation> resultPage = conversationService.lambdaQuery()
                    .eq(ChatConversation::getDelFlag, "0")
                    .orderByDesc(ChatConversation::getUpdateTime)
                    .page(page);

            List<SessionListVO> sessionList = resultPage.getRecords().stream()
                    .map(conv -> {
                        Integer count = messageService.lambdaQuery()
                                .eq(ChatMessage::getConversationId, conv.getId())
                                .eq(ChatMessage::getDelFlag, "0")
                                .count().intValue();
                        return SessionListVO.fromConversation(conv, count);
                    })
                    .collect(Collectors.toList());

            PageResult<SessionListVO> pageResult = PageResult.<SessionListVO>builder()
                    .pageNum(pageNum).pageSize(pageSize).total(resultPage.getTotal()).records(sessionList).build();
            return BaseResult.newSuccess(pageResult);
        } catch (Exception e) {
            log.error("查询会话列表失败", e);
            return BaseResult.newError("查询会话列表失败: " + e.getMessage());
        }
    }

    @DeleteMapping("/{conversationId}")
    @Transactional(rollbackFor = Exception.class)
    @Operation(summary = "删除会话", description = "删除指定会话及其关联数据")
    public BaseResult<String> deleteSession(@PathVariable String conversationId) {
        log.info("删除会话: conversationId={}", conversationId);
        try {
            ChatConversation conversation = conversationService.getBySessionId(conversationId);
            if (conversation == null) return BaseResult.newError("会话不存在");

            conversationService.lambdaUpdate()
                    .eq(ChatConversation::getId, conversation.getId())
                    .set(ChatConversation::getDelFlag, "1")
                    .update();

            messageService.lambdaUpdate()
                    .eq(ChatMessage::getConversationId, conversation.getId())
                    .set(ChatMessage::getDelFlag, "1")
                    .update();

            return BaseResult.newSuccess("会话删除成功");
        } catch (Exception e) {
            log.error("删除会话失败: conversationId={}", conversationId, e);
            return BaseResult.newError("删除会话失败: " + e.getMessage());
        }
    }

    private List<MessageVO> buildMessageVOs(List<ChatMessage> messages) {
        List<MessageVO> result = new ArrayList<>();
        MessageVO current = null;
        for (ChatMessage msg : messages) {
            if ("user".equals(msg.getMessageType())) {
                current = MessageVO.builder()
                        .id(msg.getId())
                        .question(msg.getContent())
                        .createTime(msg.getCreateTime())
                        .fileid(msg.getFileid())
                        .build();
                result.add(current);
            } else if ("assistant".equals(msg.getMessageType())) {
                if (current != null) {
                    current.setAnswer(msg.getContent());
                    current.setThinking(msg.getThinking());
                    current.setTools(msg.getTools());
                    current.setReference(msg.getReference());
                    current.setRecommend(msg.getRecommend());
                }
            }
        }
        return result;
    }
}
