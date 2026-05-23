package com.roydon.dear.knowledge.process;

import com.roydon.dear.common.util.FileUtil;
import com.roydon.dear.core.service.FileStorage;
import com.roydon.dear.knowledge.domain.entity.KnowledgeFileDO;
import com.roydon.dear.knowledge.enums.FileMineType;
import com.roydon.dear.knowledge.enums.KnowledgeFileStatus;
import com.roydon.dear.knowledge.service.IKnowledgeFileService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Markdown文件处理服务
 * MinerU可以同时处理md和pdf，可考虑聚合
 * <p>
 * 🚨1、如果 md文件是手动创建维护的，那么上传文件调用的上传接口会自动进行ocr生成图片描述，不需要再次处理。 图片格式: ![](xxx.png) 或 ![alt](xxx.png)
 * 2、如果 md文件是导入的，则需要在导入环节判断图片，并进行图片ocr识别生成图片描述。
 *
 * @AUTHOR: roydon
 * @DATE: 2026/5/20
 **/
@Slf4j
@Service
@RequiredArgsConstructor
public class MarkdownFileProcessStrategy implements FileProcessStrategy {
    private final ImageProcess imageProcess;
    private final FileStorage fileStorage;
    private final IKnowledgeFileService knowledgeFileService;

    private static final String PROCESSED_MINIO_KEY_PREFIX = "knowledge/processed";

    /**
     * 判断是否支持该文件
     */
    @Override
    public boolean supports(FileMineType mineType) {
        return FileMineType.TEXT_MARKDOWN == mineType;
    }

    /**
     * 处理文档转换 - Markdown 格式
     */
    @Override
    public KnowledgeFileDO processFile(KnowledgeFileDO fileDO) {
        // 0.开始处理md文件
        log.info("开始处理 Markdown 文件: {}", fileDO.getName());
        // 1.解析图片
        String processedMdContent = processMarkdownImages(fileDO.getContent());
        log.debug("处理 Markdown 文件成功: {}", fileDO.getName());
        // 3.上传minio
        byte[] bytes = processedMdContent.getBytes(StandardCharsets.UTF_8);
        ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(bytes);

        String storeKey = PROCESSED_MINIO_KEY_PREFIX + "/" + UUID.randomUUID().toString().replace("-", "") + "." + FileUtil.getFileExtension(fileDO.getStoragePath());
        fileStorage.upload(fileStorage.getDefaultBucket(), storeKey, byteArrayInputStream, bytes.length, fileDO.getMineType(), false);
        log.debug("上传 Markdown 文件成功: {}", fileDO.getName());
        // 4.更新文档状态
        fileDO.setStatus(KnowledgeFileStatus.CONVERTED);
        fileDO.setProcessedStoragePath(storeKey);
        knowledgeFileService.updateById(fileDO);
        // todo 优化，update失败采用重试机制
        log.debug("更新文档状态成功: {}", fileDO.getName());
        return fileDO;
    }

    /**
     * 处理 Markdown 中的图片标签：替换地址并生成图片描述
     * 匹配格式: ![](xxx.png) 或 ![alt](xxx.png)
     */
    private String processMarkdownImages(String mdContent) {
        // 匹配图片标签的正则表达式: ![alt](path)
        Pattern pattern = Pattern.compile("!\\[(.*?)\\]\\(([^)]+)\\)");
        Matcher matcher = pattern.matcher(mdContent);

        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            String altText = matcher.group(1);
            String imagePath = matcher.group(2);

            // 生成图片描述
            String imageDescription = null;
            try {
                imageDescription = imageProcess.image2Text(imagePath);
            } catch (URISyntaxException e) {
                log.debug("图片描述生成失败: {}", e.getMessage());
                matcher.appendReplacement(result, Matcher.quoteReplacement(matcher.group(0)));
                continue;
            }

            // 构建新的图片标签: ![描述](imagePath)
            String newImageTag = "![" + imageDescription + "](" + imagePath + ")";
            matcher.appendReplacement(result, Matcher.quoteReplacement(newImageTag));

            log.info("图片标签已处理: {} -> {}", imagePath, imagePath);
        }
        matcher.appendTail(result);

        return result.toString();
    }

    /**
     * 获取图片的 Content-Type
     */
    private String getImageContentType(String fileName) {
        String lowerName = fileName.toLowerCase();
        if (lowerName.endsWith(".png")) return "image/png";
        if (lowerName.endsWith(".jpg") || lowerName.endsWith(".jpeg")) return "image/jpeg";
        if (lowerName.endsWith(".gif")) return "image/gif";
        if (lowerName.endsWith(".webp")) return "image/webp";
        if (lowerName.endsWith(".bmp")) return "image/bmp";
        return "application/octet-stream";
    }
    /**
     * 生成图片描述
     * 需要注意的是，如果你用的是外部的模型，这个url需要是公网可以访问的url。否则模型需要能和MinIO进行内网通信。
     */
//    public String generateImageDescription(String imageUrl) {
//        OpenAiChatModel chatModel = OpenAiChatModel.builder()
//                .apiKey(chatModelApiKey)
//                .baseUrl(chatModelBaseUrl)
//                .modelName("qwen3-vl-plus")
//                .temperature(0.7)
//                .logResponses(true)
//                .logRequests(true)
//                .build();
//
//        UserMessage userMessage = UserMessage.from(new TextContent("请描述这张图片的内容，包括场景、对象、布局、颜色、文字信息，直接输出纯文本描述，不要多余说明。"), new ImageContent(imageUrl));
//        return chatModel.chat(userMessage).aiMessage().text();
//    }

}
