package com.roydon.dear.knowledge.process;

import com.roydon.dear.model.registry.ModelRegistry;
import com.roydon.dear.session.enums.ModelCategoryEnum;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeTypeUtils;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;

/**
 * FileImageProcess
 *
 * @AUTHOR: roydon
 * @DATE: 2026/5/23
 **/
@Service
@RequiredArgsConstructor
public class ImageProcess {
    private final ModelRegistry modelRegistry;

    /**
     * 识别图片内容
     *
     * @param fileUrl 图片文件
     * @return 图片内容的详细描述
     */
    public String image2Text(String fileUrl) throws URISyntaxException {
        if (fileUrl == null || fileUrl.isEmpty()) {
            throw new RuntimeException("图片文件内容为空");
        }
        ChatModel multimodalChatModel = modelRegistry.getDefaultChatModel(ModelCategoryEnum.MULTI.getCode());
        var userMessage = UserMessage.builder()
                .text("请描述这张图片的内容，包括场景、对象、布局、颜色、文字信息，直接输出纯文本描述，不要多余说明。")
                .media(List.of(new Media(MimeTypeUtils.IMAGE_PNG, new URI(fileUrl))))
                .build();
        var response = multimodalChatModel.call(new Prompt(List.of(userMessage)));
        String multiResp = response.getResult().getOutput().getText();

        if (multiResp == null || multiResp.trim().isEmpty()) {
            return "[无法识别图片内容]";
        }
        return multiResp.trim();
    }
}
