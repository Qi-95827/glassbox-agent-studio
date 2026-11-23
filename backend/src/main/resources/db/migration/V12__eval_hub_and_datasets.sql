-- Eval Lab V2 Phase 2：Benchmark Hub 下载包状态与用户自定义评测集。
-- 只新增本阶段对象，不修改历史 V1~V11。

CREATE TABLE IF NOT EXISTS eval_benchmark_packs (
  id VARCHAR(36) PRIMARY KEY,
  pack_id VARCHAR(60) NOT NULL UNIQUE,
  category VARCHAR(40) NOT NULL,
  display_name VARCHAR(160) NOT NULL,
  source_url VARCHAR(1000) NOT NULL,
  license VARCHAR(60),
  status VARCHAR(30) NOT NULL,
  local_path VARCHAR(1000),
  sha256 VARCHAR(64),
  sample_count INT DEFAULT 0 NOT NULL CHECK(sample_count >= 0),
  size_bytes BIGINT DEFAULT 0 NOT NULL CHECK(size_bytes >= 0),
  downloaded_at VARCHAR(40),
  error_message VARCHAR(1000),
  updated_at VARCHAR(40) NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_eval_benchmark_packs_category
  ON eval_benchmark_packs(category, status);

CREATE TABLE IF NOT EXISTS eval_custom_datasets (
  id VARCHAR(36) PRIMARY KEY,
  owner_scope_key VARCHAR(80) NOT NULL,
  code VARCHAR(40) NOT NULL,
  name VARCHAR(120) NOT NULL,
  category VARCHAR(40) NOT NULL,
  description VARCHAR(500),
  created_at VARCHAR(40) NOT NULL,
  updated_at VARCHAR(40) NOT NULL,
  UNIQUE(owner_scope_key, code)
);

CREATE TABLE IF NOT EXISTS eval_custom_samples (
  id VARCHAR(36) PRIMARY KEY,
  dataset_id VARCHAR(36) NOT NULL,
  sample_key VARCHAR(80) NOT NULL,
  title_zh VARCHAR(200),
  title_en VARCHAR(200),
  prompt CLOB NOT NULL,
  setup_json CLOB NOT NULL,
  assertions_json CLOB NOT NULL,
  rubric_json CLOB NOT NULL,
  created_at VARCHAR(40) NOT NULL,
  updated_at VARCHAR(40) NOT NULL,
  CONSTRAINT fk_eval_custom_sample_dataset FOREIGN KEY (dataset_id)
    REFERENCES eval_custom_datasets(id) ON DELETE CASCADE,
  UNIQUE(dataset_id, sample_key)
);

CREATE INDEX IF NOT EXISTS idx_eval_custom_samples_dataset
  ON eval_custom_samples(dataset_id);
