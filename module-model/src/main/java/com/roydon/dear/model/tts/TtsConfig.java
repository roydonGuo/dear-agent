package com.roydon.dear.model.tts;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "alibaba.dashscope.tts")
public class TtsConfig {

    private String apiKey;
    private String baseUrl;
    private String model = "qwen3-tts-flash";
    private String voice = "Cherry";
    private String languageType = "Chinese";
    /** 语速 0.5~2.0，默认 1.0 */
    private Double speed = 1.5;
    /** 音调 -12.0~12.0，默认 0 */
    private Double pitch = 0.0;
    /** 情感风格: neutral / happy / sad */
    private String emotion = "neutral";
}
