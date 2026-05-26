package com.roydon.dear.knowledge.process;

import com.roydon.dear.model.registry.ModelRegistry;
import com.roydon.dear.session.enums.ModelCategoryEnum;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeTypeUtils;

import java.net.URI;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * FileImageProcess
 *
 * @AUTHOR: roydon
 * @DATE: 2026/5/23
 **/
@Slf4j
@Service
@RequiredArgsConstructor
public class ImageProcess {
    private final ModelRegistry modelRegistry;

    private static final long TIMEOUT_SECONDS = 60;

    /**
     * 识别图片内容，超时或异常时返回兜底文本
     *
     * @param fileUrl 图片文件
     * @return 图片内容的详细描述
     */
    public String image2Text(String fileUrl) {
        if (fileUrl == null || !fileUrl.startsWith("http")) {
            return "无法识别图片内容";
        }
        try {
            ChatModel multimodalChatModel = modelRegistry.getDefaultChatModel(ModelCategoryEnum.MULTI.getCode());
            var userMessage = UserMessage.builder()
                    .text("请描述这张图片的内容，包括场景、对象、布局、颜色、文字信息，直接输出纯文本描述，不要多余说明。")
                    .media(List.of(new Media(MimeTypeUtils.IMAGE_PNG, new URI(fileUrl))))
                    .build();
            var response = CompletableFuture
                    .supplyAsync(() -> multimodalChatModel.call(new Prompt(List.of(userMessage))))
                    .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            String multiResp = response.getResult().getOutput().getText();

            if (multiResp == null || multiResp.trim().isEmpty()) {
                return "无法识别图片内容";
            }
            log.info("多模态图片识别成功, fileUrl={}, response={}", fileUrl, multiResp);
            return multiResp.trim();
        } catch (Exception e) {
            log.warn("多模态图片识别失败, fileUrl={}, error={}", fileUrl, e.getMessage());
            return "无法识别图片内容";
        }
    }
}
