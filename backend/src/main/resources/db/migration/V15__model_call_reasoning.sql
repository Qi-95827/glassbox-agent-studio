-- V1 活的玻璃盒：模型调用的思考链与流式标记。
-- 只新增列，不改动任何历史表结构与既有数据；缺省值保证历史行读取仍可用。

ALTER TABLE model_call_payloads ADD COLUMN IF NOT EXISTS reasoning_content CLOB;
ALTER TABLE model_call_payloads ADD COLUMN IF NOT EXISTS streamed BOOLEAN DEFAULT FALSE NOT NULL;