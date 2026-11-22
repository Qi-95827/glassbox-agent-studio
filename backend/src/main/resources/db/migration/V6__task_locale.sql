-- 记录任务创建时的语言偏好（zh-CN / en-US），驱动 Prompt 语言选择与输出语言约束。
ALTER TABLE tasks ADD COLUMN IF NOT EXISTS locale VARCHAR(16) DEFAULT 'zh-CN' NOT NULL;
