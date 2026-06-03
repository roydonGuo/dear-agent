-- v1.3: ai_chat_message 增加 knowledge 字段，存储知识库检索结果（不含 content 文本，仅含 score + metadata）
ALTER TABLE ai_chat_message
    ADD COLUMN knowledge MEDIUMTEXT NULL COMMENT '知识库检索结果' AFTER recommend;
