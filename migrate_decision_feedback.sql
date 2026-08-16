-- ========================================
-- 数据库迁移脚本：为持续学习功能新增字段
-- 执行前请先备份数据库！
-- ========================================

USE log_analysis;

-- 1. 为 decision_feedback 表新增字段
ALTER TABLE decision_feedback 
ADD COLUMN IF NOT EXISTS log_content TEXT COMMENT '日志内容',
ADD COLUMN IF NOT EXISTS log_level VARCHAR(50) COMMENT '日志级别',
ADD COLUMN IF NOT EXISTS log_template VARCHAR(500) COMMENT '日志模板ID',
ADD COLUMN IF NOT EXISTS model_confidence DOUBLE COMMENT '模型置信度',
ADD COLUMN IF NOT EXISTS is_trained TINYINT(1) DEFAULT 0 COMMENT '是否已用于训练：0-未训练，1-已训练';

-- 2. 添加索引以提升查询性能
CREATE INDEX IF NOT EXISTS idx_is_trained ON decision_feedback(is_trained);
CREATE INDEX IF NOT EXISTS idx_create_time ON decision_feedback(create_time);

-- 3. 验证字段是否添加成功
SHOW COLUMNS FROM decision_feedback;

-- 4. 查看索引
SHOW INDEX FROM decision_feedback;

SELECT '数据库迁移完成！' AS message;
