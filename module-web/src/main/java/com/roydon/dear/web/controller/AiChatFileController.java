package com.roydon.dear.web.controller;

import com.roydon.dear.common.BaseResult;
import com.roydon.dear.core.service.FileStorage;
import com.roydon.dear.model.registry.ModelRegistry;
import com.roydon.dear.session.entity.AiChatFile;
import com.roydon.dear.session.enums.ModelCategoryEnum;
import com.roydon.dear.session.service.IAiChatFileService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.IOUtils;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.MimeTypeUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 文件元数据表，存储文件基本信息和解析后的内容(AiChatFile)表控制层
 *
 * @author roydon
 * @since 2026-05-20 21:41:49
 */
@Slf4j
@RestController
@RequestMapping("/chat-file")
@RequiredArgsConstructor
public class AiChatFileController {
    private final FileStorage fileStorage;
    private final IAiChatFileService aiChatFileService;
    private final ModelRegistry modelRegistry;

    /**
     * agent上传文件
     * 支持上传：
     * 1、图片：png/jpg/jpeg
     * 2、文件：pdf/doc/docx/xls/xlsx/ppt/pptx/txt/md
     *
     * @param file 文件
     * @return 文件url
     */
    @PostMapping("/upload")
    @Transactional(rollbackFor = Exception.class)
    public BaseResult<AiChatFile> upload(@RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) {
            return BaseResult.newError("文件不能为空");
        }
        String url = fileStorage.upload(file, "chat", true);
        String originalFilename = file.getOriginalFilename();
        String extension = "";
        if (originalFilename != null && originalFilename.contains(".")) {
            extension = originalFilename.substring(originalFilename.lastIndexOf("."));
        }
        // 保存文件
        AiChatFile aiChatFile = AiChatFile.builder()
                .fileName(originalFilename)
                .createdTime(LocalDateTime.now())
                .fileSize(file.getSize())
                .fileType(extension)
                .minioPath(url)
                .build();

        switch (extension) {
            case ".png", ".jpg", ".jpeg" -> {
                // 1. 创建多模态模型识别图片
                String text;
                try {
                    text = image2Text(aiChatFile.getMinioPath());
                } catch (URISyntaxException e) {
                    throw new RuntimeException(e);
                }
                // 2. 将识别内容保存到文件表
                aiChatFile.setExtractedText(text);
                log.debug("图片识别结果：{}", text);
            }
            case ".pdf", ".doc", ".docx", ".txt", ".md" -> {
                // todo 识别文件
                throw new RuntimeException("暂不支持的文件类型");
            }
            default -> {
                return BaseResult.newError("不支持的文件类型");
            }
        }
        aiChatFileService.save(aiChatFile);

        return BaseResult.newSuccess(aiChatFile);
    }

    /**
     * 识别图片内容
     *
     * @param fileUrl 图片文件
     * @return 图片内容的详细描述
     */
    private String image2Text(String fileUrl) throws URISyntaxException {
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

