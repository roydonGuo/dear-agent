-- v1.4: reference 字段扩展为 MEDIUMTEXT，防止多 Agent 协同场景下搜索引用数据过长导致写入失败
ALTER TABLE ai_chat_message
    MODIFY COLUMN reference MEDIUMTEXT NULL COMMENT '搜索引用结果';
