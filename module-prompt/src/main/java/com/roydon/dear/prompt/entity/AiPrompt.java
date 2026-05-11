package com.roydon.dear.prompt.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("ai_prompt")
public class AiPrompt {

    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    @TableField("title")
    private String title;

    @TableField("avatar")
    private String avatar;

    @TableField("avatar_key")
    private String avatarKey;

    @TableField("description")
    private String description;

    @TableField("prompt")
    private String prompt;

    @TableField("category_ids")
    private String categoryIds;

    @TableField("create_time")
    private LocalDateTime createTime;

    @TableField("update_time")
    private LocalDateTime updateTime;
}
