package com.roydon.dear.session.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("mcp_server_config")
public class McpServerConfig {

    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    @TableField("name")
    private String name;

    @TableField("label")
    private String label;

    /** http / stdio */
    @TableField("transport")
    private String transport;

    @TableField("mcp_url")
    private String mcpUrl;

    @TableField("api_key")
    private String apiKey;

    @TableField("command")
    private String command;

    @TableField("args")
    private String args;

    @TableField("env")
    private String env;

    @TableField("description")
    private String description;

    @TableField("enabled")
    private Boolean enabled;

    @TableField("sort_order")
    private Integer sortOrder;

    @TableField("timeout_sec")
    private Integer timeoutSec;

    @TableField("create_time")
    private LocalDateTime createTime;

    @TableField("update_time")
    private LocalDateTime updateTime;
}
