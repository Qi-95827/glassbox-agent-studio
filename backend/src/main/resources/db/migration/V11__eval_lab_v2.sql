-- Eval Lab V2 Phase 1：评测批次、实验配置快照与逐题双轨裁判结果。
-- 只新增本阶段对象，不修改历史 V1~V10。

CREATE TABLE IF NOT EXISTS eval_lab_runs (
  id VARCHAR(36) PRIMARY KEY,
  owner_scope_key VARCHAR(80) NOT NULL,
  status VARCHAR(30) NOT NULL,
  source_kind VARCHAR(30) NOT NULL,
  source_key VARCHAR(80),
  categories_json CLOB NOT NULL,
  config_json CLOB NOT NULL,
  config_sha256 VARCHAR(64) NOT NULL,
  summary_json CLOB,
  judge_enabled BOOLEAN DEFAULT FALSE NOT NULL,
  judge_connection_id VARCHAR(36),
  total_cost_usd DOUBLE DEFAULT 0 NOT NULL CHECK(total_cost_usd >= 0),
  total_tokens INT DEFAULT 0 NOT NULL CHECK(total_tokens >= 0),
  total_duration_ms BIGINT DEFAULT 0 NOT NULL CHECK(total_duration_ms >= 0),
  error_message VARCHAR(1000),
  locale VARCHAR(10) DEFAULT 'zh-CN' NOT NULL,
  created_at VARCHAR(40) NOT NULL,
  started_at VARCHAR(40),
  finished_at VARCHAR(40)
);

CREATE INDEX IF NOT EXISTS idx_eval_lab_runs_owner_created
  ON eval_lab_runs(owner_scope_key, created_at);
CREATE INDEX IF NOT EXISTS idx_eval_lab_runs_status ON eval_lab_runs(status);

CREATE TABLE IF NOT EXISTS eval_lab_case_results (
  id VARCHAR(36) PRIMARY KEY,
  eval_run_id VARCHAR(36) NOT NULL,
  category VARCHAR(40) NOT NULL,
  sample_id VARCHAR(80) NOT NULL,
  sample_title_zh VARCHAR(200),
  sample_title_en VARCHAR(200),
  source_pack VARCHAR(80),
  repeat_index INT DEFAULT 1 NOT NULL CHECK(repeat_index >= 1),
  linked_run_id VARCHAR(36),
  passed BOOLEAN DEFAULT FALSE NOT NULL,
  code_score DOUBLE DEFAULT 0 NOT NULL CHECK(code_score >= 0),
  judge_score DOUBLE,
  metrics_json CLOB NOT NULL,
  assertions_json CLOB NOT NULL,
  judge_verdict_json CLOB,
  trace_snapshot_json CLOB,
  raw_output CLOB,
  duration_ms BIGINT DEFAULT 0 NOT NULL CHECK(duration_ms >= 0),
  input_tokens INT DEFAULT 0 NOT NULL CHECK(input_tokens >= 0),
  output_tokens INT DEFAULT 0 NOT NULL CHECK(output_tokens >= 0),
  cost_usd DOUBLE DEFAULT 0 NOT NULL CHECK(cost_usd >= 0),
  created_at VARCHAR(40) NOT NULL,
  CONSTRAINT fk_eval_lab_case_run FOREIGN KEY (eval_run_id)
    REFERENCES eval_lab_runs(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_eval_lab_cases_run_category
  ON eval_lab_case_results(eval_run_id, category);
CREATE INDEX IF NOT EXISTS idx_eval_lab_cases_sample
  ON eval_lab_case_results(eval_run_id, sample_id, repeat_index);
