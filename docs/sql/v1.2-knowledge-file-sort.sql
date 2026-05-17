-- 知识库文件树拖拽排序支持
-- 添加 sort 字段用于同级节点排序

ALTER TABLE ai_knowledge_file
    ADD COLUMN sort INT DEFAULT 0 NOT NULL;

CREATE INDEX idx_knowledge_file_sort ON ai_knowledge_file (base_id, parent_id, sort);
