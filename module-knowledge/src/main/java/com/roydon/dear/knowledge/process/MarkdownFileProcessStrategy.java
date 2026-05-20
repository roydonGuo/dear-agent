package com.roydon.dear.knowledge.process;

import com.roydon.dear.knowledge.domain.entity.KnowledgeFileDO;
import com.roydon.dear.knowledge.enums.FileMineType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Markdown文件处理服务
 * MinerU可以同时处理md和pdf，可考虑聚合
 *
 *🚨1、如果 md文件是手动创建维护的，那么上传文件调用的上传接口会自动进行ocr生成图片描述，不需要再次处理。 图片格式: ![](xxx.png) 或 ![alt](xxx.png)
 * 2、如果 md文件是导入的，则需要在导入环节判断图片，并进行图片ocr识别生成图片描述。
 *
 * @AUTHOR: roydon
 * @DATE: 2026/5/20
 **/
@Slf4j
@Service
public class MarkdownFileProcessStrategy implements FileProcessStrategy {

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
    public void processFile(KnowledgeFileDO fileDO, InputStream inputStream) {
        // 0.开始处理md文件

        // 1.解析图片

        // 2.替换原图片

        // 3.上传minio
    }

    /**
     * 处理 Markdown 中的图片标签：替换地址并生成图片描述
     * 匹配格式: ![](xxx.png) 或 ![alt](xxx.png)
     */
//    private String processMarkdownImages(String mdContent, java.util.Map<String, String> imageUrlMap) {
//        // 匹配图片标签的正则表达式: ![alt](path)
//        Pattern pattern = Pattern.compile("!\\[(.*?)\\]\\(([^)]+)\\)");
//        Matcher matcher = pattern.matcher(mdContent);
//
//        StringBuffer result = new StringBuffer();
//        while (matcher.find()) {
//            String altText = matcher.group(1);
//            String imagePath = matcher.group(2);
//
//            // 提取图片文件名
//            String imageName = Paths.get(imagePath).getFileName().toString();
//
//            // 获取 MinIO 上的图片 URL
//            String minioUrl = imageUrlMap.get(imageName);
//            if (minioUrl == null) {
//                // 如果找不到对应的 MinIO URL，保持原样
//                log.warn("未找到图片 {} 对应的 MinIO URL", imageName);
//                matcher.appendReplacement(result, Matcher.quoteReplacement(matcher.group(0)));
//                continue;
//            }
//
//            // 生成图片描述（mock 实现）
//            String imageDescription = generateImageDescription(minioUrl);
//
//            // 构建新的图片标签: ![描述](minio_url)
//            String newImageTag = "![" + imageDescription + "](" + minioUrl + ")";
//            matcher.appendReplacement(result, Matcher.quoteReplacement(newImageTag));
//
//            log.info("图片标签已处理: {} -> {}", imagePath, minioUrl);
//        }
//        matcher.appendTail(result);
//
//        return result.toString();
//    }

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
