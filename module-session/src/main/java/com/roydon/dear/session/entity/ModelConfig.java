package com.roydon.dear.session.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@TableName("model_config")
public class ModelConfig implements Serializable {

    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    @TableField("name")
    private String name;

    @TableField("label")
    private String label;

    /**
     * openai / dashscope / ollama
     */
    @TableField("provider")
    private String provider;

    @TableField("base_url")
    private String baseUrl;

    @TableField("api_key")
    private String apiKey;

    @TableField("model")
    private String model;

    @TableField("temperature")
    private Double temperature;

    @TableField("max_tokens")
    private Integer maxTokens;

    @TableField("top_p")
    private Double topP;

    @TableField("dimensions")
    private Integer dimensions;

    @TableField("json_response")
    private Boolean jsonResponse;

    /**
     * chat / embedding / tts / image
     */
    @TableField("category")
    private String category;

    @TableField("enabled")
    private Boolean enabled;

//    @TableField("default_model") todo
//    private Boolean defaultModel;

    @TableField("sort_order")
    private Integer sortOrder;

    @TableField("description")
    private String description;

    @TableField("create_time")
    private LocalDateTime createTime;

    @TableField("update_time")
    private LocalDateTime updateTime;
}
