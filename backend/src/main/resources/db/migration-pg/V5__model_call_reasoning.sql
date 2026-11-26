-- V1 活的玻璃盒：模型调用的思考链与流式标记（PostgreSQL 等价迁移）。
-- PostgreSQL 16 的 ALTER TABLE 不支持 ADD COLUMN IF NOT EXISTS，因此直接追加列；
-- 该列在 V1 基线建表中不存在，重复执行同样会显式失败，而不是静默跳过。

ALTER TABLE model_call_payloads ADD COLUMN reasoning_content TEXT;
ALTER TABLE model_call_payloads ADD COLUMN streamed BOOLEAN NOT NULL DEFAULT FALSE;