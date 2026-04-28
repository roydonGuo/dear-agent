package com.roydon.dear.tts;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 阿里云 TTS 配置
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "alibaba.dashscope.tts")
public class TtsConfig {

    private String apiKey;
    private String baseUrl;
    private String model = "qwen3-tts-flash";
    private String voice = "Cherry";
    private String languageType = "Chinese";
}
