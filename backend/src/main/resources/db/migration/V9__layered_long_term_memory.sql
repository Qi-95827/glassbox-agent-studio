-- Phase 3：三层长期记忆与混合召回。只扩展既有记忆表，不新增顶层表。
ALTER TABLE memory_records ADD COLUMN IF NOT EXISTS memory_type VARCHAR(20) DEFAULT 'SEMANTIC' NOT NULL;
ALTER TABLE memory_records ADD COLUMN IF NOT EXISTS source_run_id VARCHAR(36);
ALTER TABLE memory_records ADD COLUMN IF NOT EXISTS path_patterns_json VARCHAR(2000) DEFAULT '[]' NOT NULL;
ALTER TABLE memory_records ADD COLUMN IF NOT EXISTS confidence DOUBLE DEFAULT 0.7 NOT NULL;
ALTER TABLE memory_records ADD COLUMN IF NOT EXISTS hit_count INT DEFAULT 0 NOT NULL;
ALTER TABLE memory_records ADD COLUMN IF NOT EXISTS last_hit_at VARCHAR(40);

CREATE INDEX IF NOT EXISTS idx_memory_records_workspace_status ON memory_records(workspace_id, status, memory_type);
CREATE INDEX IF NOT EXISTS idx_memory_records_source_run ON memory_records(source_run_id);

ALTER TABLE run_memory_references ADD COLUMN IF NOT EXISTS memory_type VARCHAR(20) DEFAULT 'SEMANTIC' NOT NULL;
ALTER TABLE run_memory_references ADD COLUMN IF NOT EXISTS score DOUBLE DEFAULT 0 NOT NULL;
ALTER TABLE run_memory_references ADD COLUMN IF NOT EXISTS matched_reason VARCHAR(500);
