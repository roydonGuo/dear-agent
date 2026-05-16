-- ============================================================
-- 知识库多格式文件支持 - 数据库迁移脚本
-- 版本: v1.1
-- 日期: 2026-05-16
-- 说明: 新增 storage_path 和 file_size 字段，支持 MinIO 文件存储
-- ============================================================

ALTER TABLE ai_knowledge_file
    ADD COLUMN storage_path VARCHAR(500) NULL COMMENT 'MinIO存储路径(key)，非空表示文件存储在MinIO中',
    ADD COLUMN file_size    BIGINT       NULL COMMENT '文件大小（字节）';

-- 为 storage_path 创建索引（可选，用于查询存储在 MinIO 的文件）
CREATE INDEX idx_knowledge_file_storage_path ON ai_knowledge_file (storage_path(255));
