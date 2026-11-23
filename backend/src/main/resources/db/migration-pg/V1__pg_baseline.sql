-- PostgreSQL 16 基线（由 H2 V1~V13 最终结构转换而来）。
-- 仅 postgres Profile 使用；H2 默认测试继续执行 db/migration 的 V1~V13。
-- 历史迁移包含 H2 专属语法（ADD CONSTRAINT IF NOT EXISTS / CLOB），故 PG 使用等价基线。

CREATE TABLE IF NOT EXISTS agentscope_sessions (
  session_id VARCHAR(255) NOT NULL,
  state_key VARCHAR(255) NOT NULL,
  item_index INTEGER DEFAULT 0 NOT NULL,
  state_data TEXT NOT NULL,
  version BIGINT DEFAULT 0 NOT NULL,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (session_id, state_key, item_index)
);

CREATE TABLE IF NOT EXISTS agent_assemblies (
  agent_id VARCHAR(36) NOT NULL,
  recorded_at VARCHAR(40) NOT NULL,
  payload TEXT NOT NULL,
  PRIMARY KEY (agent_id)
);

CREATE TABLE IF NOT EXISTS agent_invocations (
  id VARCHAR(36) NOT NULL,
  run_id VARCHAR(36) NOT NULL,
  role_name VARCHAR(80) NOT NULL,
  task_goal VARCHAR(500) NOT NULL,
  status VARCHAR(30) NOT NULL,
  result VARCHAR(2000),
  created_at VARCHAR(40) NOT NULL,
  updated_at VARCHAR(40) NOT NULL,
  role_code VARCHAR(30),
  role_version INTEGER,
  definition_id VARCHAR(64),
  definition_version INTEGER,
  definition_sha256 VARCHAR(64),
  PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS agent_skills (
  agent_id VARCHAR(36) NOT NULL,
  skill_id VARCHAR(40) NOT NULL,
  skill_version INTEGER NOT NULL,
  content_sha256 VARCHAR(64) NOT NULL,
  created_at VARCHAR(40) NOT NULL,
  PRIMARY KEY (agent_id, skill_id)
);

CREATE TABLE IF NOT EXISTS agent_tool_grants (
  agent_id VARCHAR(36) NOT NULL,
  tool_kind VARCHAR(40) NOT NULL,
  tool_version INTEGER,
  PRIMARY KEY (agent_id, tool_kind)
);

CREATE TABLE IF NOT EXISTS agent_tool_loads (
  agent_id VARCHAR(36) NOT NULL,
  loaded_at VARCHAR(40) NOT NULL,
  PRIMARY KEY (agent_id)
);

CREATE TABLE IF NOT EXISTS approvals (
  id VARCHAR(36) NOT NULL,
  workspace_id VARCHAR(36) NOT NULL,
  fingerprint VARCHAR(64) NOT NULL,
  status VARCHAR(30) NOT NULL,
  created_at VARCHAR(40) NOT NULL,
  updated_at VARCHAR(40) NOT NULL,
  PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS audit_events (
  id VARCHAR(36) NOT NULL,
  actor VARCHAR(80) NOT NULL,
  action VARCHAR(60) NOT NULL,
  target_type VARCHAR(40) NOT NULL,
  target_id VARCHAR(80),
  outcome VARCHAR(20) NOT NULL,
  detail VARCHAR(1000),
  created_at VARCHAR(40) NOT NULL,
  PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS bug_checks (
  id VARCHAR(36) NOT NULL,
  bug_id VARCHAR(80) NOT NULL,
  workspace_id VARCHAR(36) NOT NULL,
  kind VARCHAR(30) NOT NULL,
  status VARCHAR(30) NOT NULL,
  output TEXT,
  duration_ms BIGINT NOT NULL,
  created_at VARCHAR(40) NOT NULL,
  workspace_fingerprint VARCHAR(64),
  run_id VARCHAR(36),
  PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS bug_check_cases (
  check_id VARCHAR(36) NOT NULL,
  sequence_no INTEGER NOT NULL,
  test_id VARCHAR(300) NOT NULL,
  result VARCHAR(30) NOT NULL,
  message TEXT,
  PRIMARY KEY (check_id, sequence_no)
);

CREATE TABLE IF NOT EXISTS bug_feedback (
  id VARCHAR(36) NOT NULL,
  bug_id VARCHAR(80) NOT NULL,
  workspace_id VARCHAR(36) NOT NULL,
  status VARCHAR(40) NOT NULL,
  note VARCHAR(1000),
  created_at VARCHAR(40) NOT NULL,
  PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS bug_intakes (
  id VARCHAR(36) NOT NULL,
  bug_id VARCHAR(80) NOT NULL,
  source VARCHAR(80) NOT NULL,
  source_version INTEGER NOT NULL,
  original_description TEXT NOT NULL,
  bug_description TEXT NOT NULL,
  expected_symptom TEXT NOT NULL,
  expected_test_id VARCHAR(300) NOT NULL,
  content_sha256 VARCHAR(64) NOT NULL,
  workspace_id VARCHAR(36),
  task_id VARCHAR(36) NOT NULL,
  run_id VARCHAR(36) NOT NULL,
  submission_seq INTEGER NOT NULL,
  duplicate_of VARCHAR(36),
  previous_intake_id VARCHAR(36),
  triage_status VARCHAR(30) NOT NULL,
  triage_reason VARCHAR(1000),
  triage_check_id VARCHAR(36),
  triage_updated_at VARCHAR(40),
  regression_status VARCHAR(30),
  regression_check_id VARCHAR(36),
  regression_updated_at VARCHAR(40),
  created_at VARCHAR(40) NOT NULL,
  PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS checkpoints (
  id VARCHAR(36) NOT NULL,
  run_id VARCHAR(36) NOT NULL,
  version INTEGER NOT NULL,
  stage VARCHAR(30) NOT NULL,
  rounds INTEGER NOT NULL,
  tools INTEGER NOT NULL,
  elapsed_ms BIGINT NOT NULL,
  tool_result VARCHAR(2000),
  event_id BIGINT NOT NULL,
  created_at VARCHAR(40) NOT NULL,
  checksum VARCHAR(64),
  engine VARCHAR(30) DEFAULT 'LEGACY' NOT NULL,
  PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS context_artifacts (
  id VARCHAR(48) NOT NULL,
  run_id VARCHAR(36),
  tool_call_id VARCHAR(120),
  title VARCHAR(300) NOT NULL,
  content TEXT NOT NULL,
  char_count INTEGER DEFAULT 0 NOT NULL,
  outline_json TEXT,
  created_at VARCHAR(40) NOT NULL,
  PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS cost_reservations (
  id VARCHAR(36) NOT NULL,
  run_id VARCHAR(36) NOT NULL,
  reserved_micros BIGINT NOT NULL,
  settled_micros BIGINT,
  status VARCHAR(20) NOT NULL,
  created_at VARCHAR(40) NOT NULL,
  updated_at VARCHAR(40) NOT NULL,
  over_budget_micros BIGINT DEFAULT 0 NOT NULL,
  price_version_id VARCHAR(36),
  PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS evaluation_batches (
  id VARCHAR(36) NOT NULL,
  suite VARCHAR(40) NOT NULL,
  status VARCHAR(30) NOT NULL,
  created_at VARCHAR(40) NOT NULL,
  finished_at VARCHAR(40),
  definition_json TEXT,
  definition_sha256 VARCHAR(64),
  suite_version INTEGER,
  config_json TEXT,
  config_sha256 VARCHAR(64),
  requested_by VARCHAR(80),
  PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS evaluation_cases (
  id VARCHAR(36) NOT NULL,
  batch_id VARCHAR(36) NOT NULL,
  case_code VARCHAR(40) NOT NULL,
  sequence_no INTEGER NOT NULL,
  status VARCHAR(30) NOT NULL,
  run_id VARCHAR(36),
  explanation VARCHAR(1000),
  updated_at VARCHAR(40) NOT NULL,
  PRIMARY KEY (id),
  CONSTRAINT constraint_c27 UNIQUE (batch_id, case_code)
);

CREATE TABLE IF NOT EXISTS evaluation_checks (
  case_id VARCHAR(36) NOT NULL,
  sequence_no INTEGER NOT NULL,
  check_code VARCHAR(50) NOT NULL,
  passed BOOLEAN NOT NULL,
  detail VARCHAR(1000) NOT NULL,
  PRIMARY KEY (case_id, check_code),
  CONSTRAINT constraint_8c8 UNIQUE (case_id, sequence_no)
);

CREATE TABLE IF NOT EXISTS evaluation_dataset_samples (
  id VARCHAR(36) NOT NULL,
  dataset_key VARCHAR(40) NOT NULL,
  code VARCHAR(40) NOT NULL,
  title VARCHAR(120) NOT NULL,
  goal VARCHAR(500) NOT NULL,
  scenario VARCHAR(20) NOT NULL,
  expected_status VARCHAR(30) NOT NULL,
  max_rounds INTEGER NOT NULL,
  max_tools INTEGER NOT NULL,
  source_kind VARCHAR(30) NOT NULL,
  source_ref VARCHAR(120),
  redacted BOOLEAN NOT NULL,
  version INTEGER NOT NULL,
  created_at VARCHAR(40) NOT NULL,
  updated_at VARCHAR(40) NOT NULL,
  PRIMARY KEY (id),
  CONSTRAINT constraint_ba UNIQUE (dataset_key, code)
);

CREATE TABLE IF NOT EXISTS evaluation_dataset_versions (
  id VARCHAR(36) NOT NULL,
  dataset_key VARCHAR(40) NOT NULL,
  version INTEGER NOT NULL,
  definition_json TEXT NOT NULL,
  definition_sha256 VARCHAR(64) NOT NULL,
  sample_count INTEGER NOT NULL,
  published_by VARCHAR(80) NOT NULL,
  created_at VARCHAR(40) NOT NULL,
  PRIMARY KEY (id),
  CONSTRAINT constraint_a4f UNIQUE (dataset_key, version)
);

CREATE TABLE IF NOT EXISTS evaluation_suite_versions (
  id VARCHAR(36) NOT NULL,
  suite VARCHAR(40) NOT NULL,
  version INTEGER NOT NULL,
  definition_json TEXT NOT NULL,
  definition_sha256 VARCHAR(64) NOT NULL,
  active BOOLEAN NOT NULL,
  created_at VARCHAR(40) NOT NULL,
  PRIMARY KEY (id),
  CONSTRAINT constraint_9e2 UNIQUE (suite, version)
);

CREATE TABLE IF NOT EXISTS eval_benchmark_packs (
  id VARCHAR(36) NOT NULL,
  pack_id VARCHAR(60) NOT NULL,
  category VARCHAR(40) NOT NULL,
  display_name VARCHAR(160) NOT NULL,
  source_url VARCHAR(1000) NOT NULL,
  license VARCHAR(60),
  status VARCHAR(30) NOT NULL,
  local_path VARCHAR(1000),
  sha256 VARCHAR(64),
  sample_count INTEGER DEFAULT 0 NOT NULL,
  size_bytes BIGINT DEFAULT 0 NOT NULL,
  downloaded_at VARCHAR(40),
  error_message VARCHAR(1000),
  updated_at VARCHAR(40) NOT NULL,
  PRIMARY KEY (id),
  CONSTRAINT constraint_3d37c CHECK (SIZE_BYTES >= CAST(0 AS BIGINT)),
  CONSTRAINT constraint_3d37 CHECK (SAMPLE_COUNT >= 0),
  CONSTRAINT constraint_3d3 UNIQUE (pack_id)
);

CREATE TABLE IF NOT EXISTS eval_custom_datasets (
  id VARCHAR(36) NOT NULL,
  owner_scope_key VARCHAR(80) NOT NULL,
  code VARCHAR(40) NOT NULL,
  name VARCHAR(120) NOT NULL,
  category VARCHAR(40) NOT NULL,
  description VARCHAR(500),
  created_at VARCHAR(40) NOT NULL,
  updated_at VARCHAR(40) NOT NULL,
  PRIMARY KEY (id),
  CONSTRAINT constraint_7fb UNIQUE (owner_scope_key, code)
);

CREATE TABLE IF NOT EXISTS eval_custom_samples (
  id VARCHAR(36) NOT NULL,
  dataset_id VARCHAR(36) NOT NULL,
  sample_key VARCHAR(80) NOT NULL,
  title_zh VARCHAR(200),
  title_en VARCHAR(200),
  prompt TEXT NOT NULL,
  setup_json TEXT NOT NULL,
  assertions_json TEXT NOT NULL,
  rubric_json TEXT NOT NULL,
  created_at VARCHAR(40) NOT NULL,
  updated_at VARCHAR(40) NOT NULL,
  PRIMARY KEY (id),
  CONSTRAINT constraint_cbb UNIQUE (dataset_id, sample_key)
);

CREATE TABLE IF NOT EXISTS eval_lab_case_results (
  id VARCHAR(36) NOT NULL,
  eval_run_id VARCHAR(36) NOT NULL,
  category VARCHAR(40) NOT NULL,
  sample_id VARCHAR(80) NOT NULL,
  sample_title_zh VARCHAR(200),
  sample_title_en VARCHAR(200),
  source_pack VARCHAR(80),
  repeat_index INTEGER DEFAULT 1 NOT NULL,
  linked_run_id VARCHAR(36),
  passed BOOLEAN DEFAULT FALSE NOT NULL,
  code_score DOUBLE PRECISION DEFAULT 0 NOT NULL,
  judge_score DOUBLE PRECISION,
  metrics_json TEXT NOT NULL,
  assertions_json TEXT NOT NULL,
  judge_verdict_json TEXT,
  trace_snapshot_json TEXT,
  raw_output TEXT,
  duration_ms BIGINT DEFAULT 0 NOT NULL,
  input_tokens INTEGER DEFAULT 0 NOT NULL,
  output_tokens INTEGER DEFAULT 0 NOT NULL,
  cost_usd DOUBLE PRECISION DEFAULT 0 NOT NULL,
  created_at VARCHAR(40) NOT NULL,
  PRIMARY KEY (id),
  CONSTRAINT constraint_329c74 CHECK (INPUT_TOKENS >= 0),
  CONSTRAINT constraint_329c7 CHECK (DURATION_MS >= CAST(0 AS BIGINT)),
  CONSTRAINT constraint_329c CHECK (CODE_SCORE >= CAST(0.0 AS DOUBLE PRECISION)),
  CONSTRAINT constraint_329 CHECK (REPEAT_INDEX >= 1),
  CONSTRAINT constraint_329c749 CHECK (OUTPUT_TOKENS >= 0),
  CONSTRAINT constraint_329c749c CHECK (COST_USD >= CAST(0.0 AS DOUBLE PRECISION))
);

CREATE TABLE IF NOT EXISTS eval_lab_runs (
  id VARCHAR(36) NOT NULL,
  owner_scope_key VARCHAR(80) NOT NULL,
  status VARCHAR(30) NOT NULL,
  source_kind VARCHAR(30) NOT NULL,
  source_key VARCHAR(80),
  categories_json TEXT NOT NULL,
  config_json TEXT NOT NULL,
  config_sha256 VARCHAR(64) NOT NULL,
  summary_json TEXT,
  judge_enabled BOOLEAN DEFAULT FALSE NOT NULL,
  judge_connection_id VARCHAR(36),
  total_cost_usd DOUBLE PRECISION DEFAULT 0 NOT NULL,
  total_tokens INTEGER DEFAULT 0 NOT NULL,
  total_duration_ms BIGINT DEFAULT 0 NOT NULL,
  error_message VARCHAR(1000),
  locale VARCHAR(10) DEFAULT 'zh-CN' NOT NULL,
  created_at VARCHAR(40) NOT NULL,
  started_at VARCHAR(40),
  finished_at VARCHAR(40),
  PRIMARY KEY (id),
  CONSTRAINT constraint_f391f CHECK (TOTAL_DURATION_MS >= CAST(0 AS BIGINT)),
  CONSTRAINT constraint_f39 CHECK (TOTAL_COST_USD >= CAST(0.0 AS DOUBLE PRECISION)),
  CONSTRAINT constraint_f391 CHECK (TOTAL_TOKENS >= 0)
);

CREATE TABLE IF NOT EXISTS events (
  id BIGINT GENERATED BY DEFAULT AS IDENTITY NOT NULL,
  run_id VARCHAR(36) NOT NULL,
  event_type VARCHAR(50) NOT NULL,
  message VARCHAR(2000) NOT NULL,
  created_at VARCHAR(40) NOT NULL,
  reason_code VARCHAR(50),
  plan_version_id VARCHAR(36),
  node_id VARCHAR(36),
  attempt_id VARCHAR(36),
  agent_id VARCHAR(36),
  check_id VARCHAR(36),
  model_call_id VARCHAR(36),
  tool_call_id VARCHAR(36),
  run_seq BIGINT,
  PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS external_actions (
  id VARCHAR(36) NOT NULL,
  run_id VARCHAR(36),
  action_key VARCHAR(120) NOT NULL,
  kind VARCHAR(40) NOT NULL,
  status VARCHAR(30) NOT NULL,
  approval_ref VARCHAR(80),
  target VARCHAR(300) NOT NULL,
  target_version VARCHAR(100),
  payload_sha256 VARCHAR(64) NOT NULL,
  result_evidence VARCHAR(2000),
  attempts INTEGER NOT NULL,
  last_checked_at VARCHAR(40),
  created_at VARCHAR(40) NOT NULL,
  updated_at VARCHAR(40) NOT NULL,
  PRIMARY KEY (id),
  CONSTRAINT constraint_677b UNIQUE (action_key)
);

CREATE TABLE IF NOT EXISTS external_action_checks (
  id VARCHAR(36) NOT NULL,
  action_id VARCHAR(36) NOT NULL,
  outcome VARCHAR(30) NOT NULL,
  evidence VARCHAR(2000),
  checked_at VARCHAR(40) NOT NULL,
  PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS git_actions (
  id VARCHAR(36) NOT NULL,
  workspace_id VARCHAR(36) NOT NULL,
  kind VARCHAR(30) NOT NULL,
  status VARCHAR(30) NOT NULL,
  approval_id VARCHAR(36) NOT NULL,
  commit_id VARCHAR(64),
  created_at VARCHAR(40) NOT NULL,
  updated_at VARCHAR(40) NOT NULL,
  target VARCHAR(300),
  detail VARCHAR(1000),
  PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS git_delivery_requests (
  id VARCHAR(36) NOT NULL,
  workspace_id VARCHAR(36) NOT NULL,
  kind VARCHAR(30) NOT NULL,
  remote VARCHAR(80) NOT NULL,
  target VARCHAR(200) NOT NULL,
  target_commit VARCHAR(64),
  head_commit VARCHAR(64) NOT NULL,
  source_branch VARCHAR(200) NOT NULL,
  remote_url_sha256 VARCHAR(64),
  status VARCHAR(30) NOT NULL,
  note VARCHAR(500),
  result_detail VARCHAR(1000),
  created_at VARCHAR(40) NOT NULL,
  updated_at VARCHAR(40) NOT NULL,
  PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS handoffs (
  id VARCHAR(36) NOT NULL,
  run_id VARCHAR(36) NOT NULL,
  agent_id VARCHAR(36) NOT NULL,
  direction VARCHAR(30) NOT NULL,
  payload TEXT NOT NULL,
  created_at VARCHAR(40) NOT NULL,
  PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS handoff_receipts (
  handoff_id VARCHAR(36) NOT NULL,
  run_id VARCHAR(36) NOT NULL,
  receiver_agent_id VARCHAR(36) NOT NULL,
  received_at VARCHAR(40) NOT NULL,
  PRIMARY KEY (handoff_id),
  CONSTRAINT constraint_54 UNIQUE (receiver_agent_id)
);

CREATE TABLE IF NOT EXISTS knowledge_chunks (
  id VARCHAR(36) NOT NULL,
  document_id VARCHAR(36) NOT NULL,
  workspace_id VARCHAR(36) NOT NULL,
  chunk_index INTEGER NOT NULL,
  section_title VARCHAR(300),
  content TEXT NOT NULL,
  token_estimate INTEGER DEFAULT 0 NOT NULL,
  embedding_json TEXT,
  embedding_model VARCHAR(120),
  created_at VARCHAR(40) NOT NULL,
  PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS knowledge_documents (
  id VARCHAR(36) NOT NULL,
  workspace_id VARCHAR(36) NOT NULL,
  owner_scope_key VARCHAR(80) NOT NULL,
  source_type VARCHAR(40) NOT NULL,
  source_path VARCHAR(1000),
  title VARCHAR(300) NOT NULL,
  content_sha256 VARCHAR(64) NOT NULL,
  chunk_count INTEGER DEFAULT 0 NOT NULL,
  status VARCHAR(30) NOT NULL,
  created_at VARCHAR(40) NOT NULL,
  updated_at VARCHAR(40) NOT NULL,
  PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS managed_evaluation_batches (
  id VARCHAR(36) NOT NULL,
  dataset_key VARCHAR(40) NOT NULL,
  dataset_version_id VARCHAR(36) NOT NULL,
  definition_sha256 VARCHAR(64) NOT NULL,
  config_json TEXT NOT NULL,
  config_sha256 VARCHAR(64) NOT NULL,
  status VARCHAR(30) NOT NULL,
  created_at VARCHAR(40) NOT NULL,
  finished_at VARCHAR(40),
  PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS managed_evaluation_cases (
  id VARCHAR(36) NOT NULL,
  batch_id VARCHAR(36) NOT NULL,
  sample_code VARCHAR(40) NOT NULL,
  title VARCHAR(120) NOT NULL,
  expected_status VARCHAR(30) NOT NULL,
  run_id VARCHAR(36),
  status VARCHAR(30) NOT NULL,
  explanation VARCHAR(1000),
  updated_at VARCHAR(40) NOT NULL,
  PRIMARY KEY (id),
  CONSTRAINT constraint_67b UNIQUE (batch_id, sample_code)
);

CREATE TABLE IF NOT EXISTS memory_history (
  memory_id VARCHAR(36) NOT NULL,
  version INTEGER NOT NULL,
  category VARCHAR(30) NOT NULL,
  content VARCHAR(300) NOT NULL,
  status VARCHAR(20) NOT NULL,
  changed_at VARCHAR(40) NOT NULL,
  PRIMARY KEY (memory_id, version)
);

CREATE TABLE IF NOT EXISTS memory_records (
  id VARCHAR(36) NOT NULL,
  workspace_id VARCHAR(36) NOT NULL,
  category VARCHAR(30) NOT NULL,
  content VARCHAR(300) NOT NULL,
  source VARCHAR(30) NOT NULL,
  status VARCHAR(20) NOT NULL,
  version INTEGER NOT NULL,
  created_at VARCHAR(40) NOT NULL,
  updated_at VARCHAR(40) NOT NULL,
  memory_type VARCHAR(20) DEFAULT 'SEMANTIC' NOT NULL,
  source_run_id VARCHAR(36),
  path_patterns_json VARCHAR(2000) DEFAULT '[]' NOT NULL,
  confidence DOUBLE PRECISION DEFAULT 0.7 NOT NULL,
  hit_count INTEGER DEFAULT 0 NOT NULL,
  last_hit_at VARCHAR(40),
  embedding_json TEXT,
  embedding_model VARCHAR(120),
  PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS model_call_payloads (
  model_call_id VARCHAR(36) NOT NULL,
  run_id VARCHAR(36) NOT NULL,
  request_messages_json TEXT,
  request_tools_json TEXT,
  response_content TEXT,
  response_tool_calls_json TEXT,
  truncated BOOLEAN DEFAULT FALSE NOT NULL,
  created_at VARCHAR(40) NOT NULL,
  context_breakdown_json TEXT,
  compaction_tier VARCHAR(30),
  PRIMARY KEY (model_call_id)
);

CREATE TABLE IF NOT EXISTS model_invocations (
  id VARCHAR(36) NOT NULL,
  run_id VARCHAR(36) NOT NULL,
  agent_id VARCHAR(36) NOT NULL,
  model_name VARCHAR(80) NOT NULL,
  status VARCHAR(30) NOT NULL,
  input_tokens INTEGER,
  output_tokens INTEGER,
  duration_ms BIGINT,
  error_message VARCHAR(1000),
  created_at VARCHAR(40) NOT NULL,
  updated_at VARCHAR(40) NOT NULL,
  PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS plan_adjustments (
  id VARCHAR(36) NOT NULL,
  run_id VARCHAR(36) NOT NULL,
  plan_version_id VARCHAR(36) NOT NULL,
  version INTEGER NOT NULL,
  base_sha256 VARCHAR(64) NOT NULL,
  proposal_json TEXT NOT NULL,
  proposal_sha256 VARCHAR(64) NOT NULL,
  status VARCHAR(20) NOT NULL,
  created_at VARCHAR(40) NOT NULL,
  applied_at VARCHAR(40),
  PRIMARY KEY (id),
  CONSTRAINT constraint_d05a UNIQUE (run_id, version)
);

CREATE TABLE IF NOT EXISTS plan_candidates (
  id VARCHAR(36) NOT NULL,
  owner_scope_key VARCHAR(80) NOT NULL,
  actor_key VARCHAR(80) NOT NULL,
  group_id VARCHAR(64) NOT NULL,
  input_version INTEGER NOT NULL,
  request_id VARCHAR(80) NOT NULL,
  source_candidate_id VARCHAR(36),
  schema_version INTEGER NOT NULL,
  input_sha256 VARCHAR(64) NOT NULL,
  input_json TEXT NOT NULL,
  workspace_fingerprint VARCHAR(64),
  file_versions_json TEXT,
  candidate_sha256 VARCHAR(64),
  candidate_json TEXT,
  status VARCHAR(20) NOT NULL,
  errors_json TEXT,
  model_name VARCHAR(80),
  input_tokens INTEGER,
  output_tokens INTEGER,
  prepared_workspace_id VARCHAR(36),
  preparation_status VARCHAR(20),
  workspace_operation_id VARCHAR(36),
  preparation_id VARCHAR(36),
  preparation_started_at VARCHAR(40),
  run_id VARCHAR(36),
  created_at VARCHAR(40) NOT NULL,
  settled_at VARCHAR(40),
  agent_bindings_json TEXT,
  agent_aliases_json TEXT,
  PRIMARY KEY (id),
  CONSTRAINT constraint_1377 CHECK (SCHEMA_VERSION = 1),
  CONSTRAINT constraint_137 UNIQUE (owner_scope_key, request_id)
);

CREATE TABLE IF NOT EXISTS plan_nodes (
  id VARCHAR(36) NOT NULL,
  run_id VARCHAR(36) NOT NULL,
  sequence_no INTEGER NOT NULL,
  role_code VARCHAR(30),
  prerequisite_role VARCHAR(30),
  status VARCHAR(30) NOT NULL,
  agent_id VARCHAR(36),
  started_at VARCHAR(40),
  finished_at VARCHAR(40),
  source_agent_id VARCHAR(36),
  definition_id VARCHAR(64),
  definition_version INTEGER,
  definition_sha256 VARCHAR(64),
  plan_version_id VARCHAR(36),
  node_key VARCHAR(64),
  node_label VARCHAR(120),
  phase_key VARCHAR(64),
  permission_role VARCHAR(30),
  workspace_read BOOLEAN DEFAULT FALSE NOT NULL,
  workspace_write BOOLEAN DEFAULT FALSE NOT NULL,
  max_node_model_calls INTEGER,
  max_node_tool_calls INTEGER,
  node_kind VARCHAR(30) DEFAULT 'AGENT' NOT NULL,
  check_id VARCHAR(64),
  check_version INTEGER,
  PRIMARY KEY (id),
  CONSTRAINT workflow_node_budget CHECK (((MAX_NODE_MODEL_CALLS IS NULL)
    OR ((PLAN_VERSION_ID IS NOT NULL)
    AND (MAX_NODE_MODEL_CALLS BETWEEN 1 AND 20)))
    AND ((MAX_NODE_TOOL_CALLS IS NULL)
    OR ((PLAN_VERSION_ID IS NOT NULL)
    AND (MAX_NODE_TOOL_CALLS BETWEEN 0 AND 20)))),
  CONSTRAINT plan_node_shape_v2 CHECK (((PLAN_VERSION_ID IS NULL)
    AND (NODE_KIND = 'AGENT')
    AND (ROLE_CODE IS NOT NULL)
    AND (PERMISSION_ROLE IS NULL)
    AND (NODE_KEY IS NULL)
    AND (NODE_LABEL IS NULL)
    AND (PHASE_KEY IS NULL))
    OR ((PLAN_VERSION_ID IS NOT NULL)
    AND (NODE_KIND = 'AGENT')
    AND (ROLE_CODE IS NULL)
    AND (PREREQUISITE_ROLE IS NULL)
    AND (PERMISSION_ROLE IS NOT NULL)
    AND (NODE_KEY IS NOT NULL)
    AND (NODE_LABEL IS NOT NULL)
    AND (PHASE_KEY IS NOT NULL)
    AND (DEFINITION_ID IS NOT NULL)
    AND (DEFINITION_VERSION IS NOT NULL)
    AND (DEFINITION_SHA256 IS NOT NULL)
    AND (CHECK_ID IS NULL)
    AND (CHECK_VERSION IS NULL))
    OR ((PLAN_VERSION_ID IS NOT NULL)
    AND (NODE_KIND = 'SYSTEM_CHECK')
    AND (ROLE_CODE IS NULL)
    AND (PREREQUISITE_ROLE IS NULL)
    AND (PERMISSION_ROLE IS NULL)
    AND (NODE_KEY IS NOT NULL)
    AND (NODE_LABEL IS NOT NULL)
    AND (PHASE_KEY IS NOT NULL)
    AND (DEFINITION_ID IS NULL)
    AND (DEFINITION_VERSION IS NULL)
    AND (DEFINITION_SHA256 IS NULL)
    AND (CHECK_ID IS NOT NULL)
    AND (CHECK_VERSION IS NOT NULL))),
  CONSTRAINT workflow_workspace_write CHECK ((WORKSPACE_WRITE = FALSE)
    OR (WORKSPACE_READ
    AND (PLAN_VERSION_ID IS NOT NULL))),
  CONSTRAINT constraint_802 UNIQUE (run_id, role_code),
  CONSTRAINT constraint_8023 UNIQUE (run_id, sequence_no),
  CONSTRAINT constraint_80233 UNIQUE (agent_id),
  CONSTRAINT workflow_node_key UNIQUE (plan_version_id, node_key)
);

CREATE TABLE IF NOT EXISTS plan_node_agent_bindings (
  node_id VARCHAR(36) NOT NULL,
  run_id VARCHAR(36) NOT NULL,
  agent_kind VARCHAR(20) NOT NULL,
  definition_id VARCHAR(64) NOT NULL,
  definition_version INTEGER NOT NULL,
  spec_sha256 VARCHAR(64),
  source VARCHAR(20) NOT NULL,
  reason VARCHAR(1000) NOT NULL,
  display_name VARCHAR(120) NOT NULL,
  components_json TEXT,
  created_at VARCHAR(40) NOT NULL,
  PRIMARY KEY (node_id)
);

CREATE TABLE IF NOT EXISTS plan_node_dependencies (
  run_id VARCHAR(36) NOT NULL,
  node_id VARCHAR(36) NOT NULL,
  prerequisite_id VARCHAR(36) NOT NULL,
  PRIMARY KEY (run_id, node_id, prerequisite_id),
  CONSTRAINT constraint_4cf15 CHECK (NODE_ID <> PREREQUISITE_ID)
);

CREATE TABLE IF NOT EXISTS plan_node_specs (
  plan_node_id VARCHAR(36) NOT NULL,
  run_id VARCHAR(36) NOT NULL,
  node_key VARCHAR(64) NOT NULL,
  candidate_sha256 VARCHAR(64) NOT NULL,
  read_files_json TEXT NOT NULL,
  write_files_json TEXT NOT NULL,
  outputs_json TEXT NOT NULL,
  acceptance_json TEXT NOT NULL,
  spec_sha256 VARCHAR(64) NOT NULL,
  PRIMARY KEY (plan_node_id),
  CONSTRAINT constraint_fadc UNIQUE (run_id, node_key)
);

CREATE TABLE IF NOT EXISTS plan_revision_edges (
  id VARCHAR(36) NOT NULL,
  run_id VARCHAR(36) NOT NULL,
  reviewer_node_id VARCHAR(36) NOT NULL,
  target_node_id VARCHAR(36) NOT NULL,
  max_revisions INTEGER DEFAULT 2 NOT NULL,
  revision_count INTEGER DEFAULT 0 NOT NULL,
  last_verdict_json TEXT,
  created_at VARCHAR(40) NOT NULL,
  updated_at VARCHAR(40) NOT NULL,
  PRIMARY KEY (id),
  CONSTRAINT constraint_29cb CHECK (MAX_REVISIONS BETWEEN 1 AND 5),
  CONSTRAINT constraint_29cbe CHECK (REVISION_COUNT >= 0),
  CONSTRAINT constraint_29cbe0 UNIQUE (run_id, reviewer_node_id)
);

CREATE TABLE IF NOT EXISTS private_agents (
  id VARCHAR(36) NOT NULL,
  owner_scope_key VARCHAR(80) NOT NULL,
  name VARCHAR(120) NOT NULL,
  description VARCHAR(300) NOT NULL,
  responsibility TEXT NOT NULL,
  output_requirements VARCHAR(2000),
  permission_role VARCHAR(20) NOT NULL,
  recommendable BOOLEAN DEFAULT FALSE NOT NULL,
  model_mode VARCHAR(20) DEFAULT 'mock' NOT NULL,
  skill_refs_json VARCHAR(4000) NOT NULL,
  tool_refs_json VARCHAR(4000) NOT NULL,
  draft_sha256 VARCHAR(64) NOT NULL,
  status VARCHAR(20) NOT NULL,
  current_version INTEGER DEFAULT 0 NOT NULL,
  created_at VARCHAR(40) NOT NULL,
  updated_at VARCHAR(40) NOT NULL,
  archived_at VARCHAR(40),
  model_strategy VARCHAR(20) DEFAULT 'INHERIT_TASK' NOT NULL,
  PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS private_agent_versions (
  agent_id VARCHAR(36) NOT NULL,
  version INTEGER NOT NULL,
  name VARCHAR(120) NOT NULL,
  description VARCHAR(300) NOT NULL,
  responsibility TEXT NOT NULL,
  output_requirements VARCHAR(2000),
  permission_role VARCHAR(20) NOT NULL,
  recommendable BOOLEAN NOT NULL,
  model_mode VARCHAR(20) NOT NULL,
  components_json TEXT NOT NULL,
  spec_sha256 VARCHAR(64) NOT NULL,
  created_at VARCHAR(40) NOT NULL,
  model_strategy VARCHAR(20) DEFAULT 'INHERIT_TASK' NOT NULL,
  PRIMARY KEY (agent_id, version)
);

CREATE TABLE IF NOT EXISTS private_skills (
  id VARCHAR(36) NOT NULL,
  owner_scope_key VARCHAR(80) NOT NULL,
  name VARCHAR(120) NOT NULL,
  summary VARCHAR(300) NOT NULL,
  usage_notes VARCHAR(2000),
  input_contract VARCHAR(1000),
  output_contract VARCHAR(1000),
  content_md TEXT NOT NULL,
  draft_sha256 VARCHAR(64) NOT NULL,
  status VARCHAR(20) NOT NULL,
  current_version INTEGER DEFAULT 0 NOT NULL,
  created_at VARCHAR(40) NOT NULL,
  updated_at VARCHAR(40) NOT NULL,
  archived_at VARCHAR(40),
  PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS private_skill_versions (
  skill_id VARCHAR(36) NOT NULL,
  version INTEGER NOT NULL,
  name VARCHAR(120) NOT NULL,
  summary VARCHAR(300) NOT NULL,
  usage_notes VARCHAR(2000),
  input_contract VARCHAR(1000),
  output_contract VARCHAR(1000),
  content_md TEXT NOT NULL,
  content_sha256 VARCHAR(64) NOT NULL,
  created_at VARCHAR(40) NOT NULL,
  PRIMARY KEY (skill_id, version)
);

CREATE TABLE IF NOT EXISTS private_tools (
  id VARCHAR(36) NOT NULL,
  owner_scope_key VARCHAR(80) NOT NULL,
  name VARCHAR(120) NOT NULL,
  description VARCHAR(300) NOT NULL,
  executor_kind VARCHAR(20) NOT NULL,
  file_extensions_json VARCHAR(2000) NOT NULL,
  draft_sha256 VARCHAR(64) NOT NULL,
  status VARCHAR(20) NOT NULL,
  current_version INTEGER DEFAULT 0 NOT NULL,
  created_at VARCHAR(40) NOT NULL,
  updated_at VARCHAR(40) NOT NULL,
  archived_at VARCHAR(40),
  PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS private_tool_versions (
  tool_id VARCHAR(36) NOT NULL,
  version INTEGER NOT NULL,
  name VARCHAR(120) NOT NULL,
  description VARCHAR(300) NOT NULL,
  executor_kind VARCHAR(20) NOT NULL,
  file_extensions_json VARCHAR(2000) NOT NULL,
  spec_sha256 VARCHAR(64) NOT NULL,
  created_at VARCHAR(40) NOT NULL,
  PRIMARY KEY (tool_id, version)
);

CREATE TABLE IF NOT EXISTS provider_price_versions (
  id VARCHAR(36) NOT NULL,
  provider VARCHAR(60) NOT NULL,
  model VARCHAR(80) NOT NULL,
  version INTEGER NOT NULL,
  currency VARCHAR(10) NOT NULL,
  input_micros_per_million BIGINT NOT NULL,
  output_micros_per_million BIGINT NOT NULL,
  input_token_upper_bound INTEGER NOT NULL,
  output_token_upper_bound INTEGER NOT NULL,
  active BOOLEAN NOT NULL,
  created_at VARCHAR(40) NOT NULL,
  PRIMARY KEY (id),
  CONSTRAINT constraint_e7 UNIQUE (provider, model, version)
);

CREATE TABLE IF NOT EXISTS recovery_leases (
  run_id VARCHAR(36) NOT NULL,
  lease_owner VARCHAR(120) NOT NULL,
  lease_token VARCHAR(36) NOT NULL,
  acquired_at VARCHAR(40) NOT NULL,
  expires_at VARCHAR(40) NOT NULL,
  status VARCHAR(20) NOT NULL,
  PRIMARY KEY (run_id)
);

CREATE TABLE IF NOT EXISTS repository_authorizations (
  id VARCHAR(36) NOT NULL,
  owner_scope_key VARCHAR(80) NOT NULL,
  repository_root VARCHAR(2000) NOT NULL,
  repository_key VARCHAR(64) NOT NULL,
  granted_by VARCHAR(36) NOT NULL,
  created_at VARCHAR(40) NOT NULL,
  PRIMARY KEY (id),
  CONSTRAINT constraint_56b UNIQUE (owner_scope_key, repository_key)
);

CREATE TABLE IF NOT EXISTS resource_ownership (
  id VARCHAR(120) NOT NULL,
  resource_type VARCHAR(30) NOT NULL,
  resource_id VARCHAR(36) NOT NULL,
  owner_scope_key VARCHAR(80) NOT NULL,
  created_at VARCHAR(40) NOT NULL,
  PRIMARY KEY (id),
  CONSTRAINT constraint_69b UNIQUE (resource_type, resource_id)
);

CREATE TABLE IF NOT EXISTS runs (
  id VARCHAR(36) NOT NULL,
  task_id VARCHAR(36) NOT NULL,
  parent_id VARCHAR(36),
  status VARCHAR(30) NOT NULL,
  reason VARCHAR(1000),
  result VARCHAR(2000),
  rounds INTEGER NOT NULL,
  tools INTEGER NOT NULL,
  max_rounds INTEGER,
  max_tools INTEGER,
  max_seconds INTEGER,
  elapsed_ms BIGINT NOT NULL,
  created_at VARCHAR(40) NOT NULL,
  updated_at VARCHAR(40) NOT NULL,
  max_per_tool INTEGER,
  max_reported_tokens BIGINT,
  max_identical_tools INTEGER,
  max_agent_rounds INTEGER,
  max_agent_tools INTEGER,
  recovery_dispatch VARCHAR(30) DEFAULT 'UNKNOWN' NOT NULL,
  stop_cause VARCHAR(40),
  PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS run_cost_limits (
  run_id VARCHAR(36) NOT NULL,
  max_cost_micros BIGINT NOT NULL,
  price_version_id VARCHAR(36) NOT NULL,
  reserved_micros BIGINT NOT NULL,
  settled_micros BIGINT NOT NULL,
  unknown_calls INTEGER NOT NULL,
  created_at VARCHAR(40) NOT NULL,
  updated_at VARCHAR(40) NOT NULL,
  overage_micros BIGINT DEFAULT 0 NOT NULL,
  model_price_versions_json VARCHAR(4000),
  PRIMARY KEY (run_id)
);

CREATE TABLE IF NOT EXISTS run_handovers (
  id VARCHAR(36) NOT NULL,
  run_id VARCHAR(36) NOT NULL,
  workspace_id VARCHAR(36) NOT NULL,
  status VARCHAR(20) NOT NULL,
  baseline_fingerprint VARCHAR(64) NOT NULL,
  current_fingerprint VARCHAR(64),
  evidence_invalidated BOOLEAN NOT NULL,
  note VARCHAR(1000),
  taken_at VARCHAR(40) NOT NULL,
  returned_at VARCHAR(40),
  PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS run_memory_loads (
  run_id VARCHAR(36) NOT NULL,
  workspace_id VARCHAR(36) NOT NULL,
  item_count INTEGER NOT NULL,
  loaded_at VARCHAR(40) NOT NULL,
  PRIMARY KEY (run_id)
);

CREATE TABLE IF NOT EXISTS run_memory_references (
  run_id VARCHAR(36) NOT NULL,
  sequence_no INTEGER NOT NULL,
  memory_id VARCHAR(36) NOT NULL,
  memory_version INTEGER NOT NULL,
  category VARCHAR(30) NOT NULL,
  memory_type VARCHAR(20) DEFAULT 'SEMANTIC' NOT NULL,
  score DOUBLE PRECISION DEFAULT 0 NOT NULL,
  matched_reason VARCHAR(500),
  PRIMARY KEY (run_id, sequence_no),
  CONSTRAINT constraint_1aa UNIQUE (run_id, memory_id)
);

CREATE TABLE IF NOT EXISTS run_snapshots (
  id VARCHAR(36) NOT NULL,
  run_id VARCHAR(36) NOT NULL,
  plan_version_id VARCHAR(36) NOT NULL,
  event_seq BIGINT NOT NULL,
  completed_nodes INTEGER NOT NULL,
  rounds INTEGER NOT NULL,
  tools INTEGER NOT NULL,
  elapsed_ms BIGINT NOT NULL,
  workspace_fingerprint VARCHAR(64),
  budget_sha256 VARCHAR(64) NOT NULL,
  boundary_sha256 VARCHAR(64) NOT NULL,
  created_at VARCHAR(40) NOT NULL,
  PRIMARY KEY (id),
  CONSTRAINT constraint_ad5 UNIQUE (run_id, event_seq)
);

CREATE TABLE IF NOT EXISTS run_supplements (
  id VARCHAR(36) NOT NULL,
  run_id VARCHAR(36) NOT NULL,
  node_id VARCHAR(36) NOT NULL,
  reason_code VARCHAR(50) NOT NULL,
  question VARCHAR(500) NOT NULL,
  kind VARCHAR(30) NOT NULL,
  status VARCHAR(30) NOT NULL,
  answer_path VARCHAR(200),
  answer_sha256 VARCHAR(64),
  task_version INTEGER NOT NULL,
  created_at VARCHAR(40) NOT NULL,
  decided_at VARCHAR(40),
  PRIMARY KEY (id),
  CONSTRAINT constraint_aa CHECK (KIND = 'TEST_FILE'),
  CONSTRAINT constraint_aa8 CHECK (STATUS IN('OPEN', 'APPLIED'))
);

CREATE TABLE IF NOT EXISTS tasks (
  id VARCHAR(36) NOT NULL,
  goal VARCHAR(500) NOT NULL,
  scenario VARCHAR(20) NOT NULL,
  created_at VARCHAR(40) NOT NULL,
  archived_at VARCHAR(40),
  model_mode VARCHAR(20) DEFAULT 'mock' NOT NULL,
  workspace_id VARCHAR(36),
  file_scope VARCHAR(2000),
  write_enabled BOOLEAN DEFAULT FALSE NOT NULL,
  bug_id VARCHAR(80),
  memory_enabled BOOLEAN DEFAULT FALSE NOT NULL,
  constraints_json VARCHAR(20000) DEFAULT '[]' NOT NULL,
  acceptance_json VARCHAR(20000) DEFAULT '[]' NOT NULL,
  entry_kind VARCHAR(30) DEFAULT 'GENERAL' NOT NULL,
  file_scopes_json VARCHAR(20000) DEFAULT '[]' NOT NULL,
  version INTEGER DEFAULT 1 NOT NULL,
  max_cost_micros BIGINT,
  model_connection_id VARCHAR(36),
  locale VARCHAR(16) DEFAULT 'zh-CN' NOT NULL,
  PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS task_entry_policies (
  task_id VARCHAR(36) NOT NULL,
  policy_version INTEGER NOT NULL,
  deliverables_json TEXT NOT NULL,
  acceptance_json TEXT NOT NULL,
  warnings_json TEXT,
  created_at VARCHAR(40) NOT NULL,
  PRIMARY KEY (task_id)
);

CREATE TABLE IF NOT EXISTS task_entry_routes (
  task_id VARCHAR(36) NOT NULL,
  rule_code VARCHAR(80) NOT NULL,
  requested_kind VARCHAR(30),
  routed_kind VARCHAR(30) NOT NULL,
  template_id VARCHAR(64),
  template_version INTEGER,
  explanation_json TEXT NOT NULL,
  decision_sha256 VARCHAR(64) NOT NULL,
  created_at VARCHAR(40) NOT NULL,
  PRIMARY KEY (task_id)
);

CREATE TABLE IF NOT EXISTS task_role_budgets (
  task_id VARCHAR(36) NOT NULL,
  role_code VARCHAR(30) NOT NULL,
  policy_version INTEGER NOT NULL,
  max_model_calls INTEGER,
  max_tool_calls INTEGER,
  PRIMARY KEY (task_id, role_code),
  CONSTRAINT constraint_391 CHECK (ROLE_CODE IN('ANALYST', 'CODER', 'REVIEWER')),
  CONSTRAINT constraint_39132b CHECK ((MAX_MODEL_CALLS IS NOT NULL)
    OR (MAX_TOOL_CALLS IS NOT NULL)),
  CONSTRAINT constraint_3913 CHECK ((MAX_MODEL_CALLS IS NULL)
    OR (MAX_MODEL_CALLS BETWEEN 1 AND 20)),
  CONSTRAINT constraint_39132 CHECK ((MAX_TOOL_CALLS IS NULL)
    OR (MAX_TOOL_CALLS BETWEEN 0 AND 20)),
  CONSTRAINT constraint_39 CHECK (POLICY_VERSION = 1)
);

CREATE TABLE IF NOT EXISTS task_source_artifacts (
  id VARCHAR(36) NOT NULL,
  task_id VARCHAR(36) NOT NULL,
  kind VARCHAR(30) NOT NULL,
  source_task_id VARCHAR(36) NOT NULL,
  source_run_id VARCHAR(36) NOT NULL,
  source_task_version INTEGER NOT NULL,
  artifact_sha256 VARCHAR(64) NOT NULL,
  summary VARCHAR(500) NOT NULL,
  content TEXT,
  file_versions_json TEXT,
  created_at VARCHAR(40) NOT NULL,
  PRIMARY KEY (id),
  CONSTRAINT constraint_fbe UNIQUE (task_id, kind, source_run_id)
);

CREATE TABLE IF NOT EXISTS task_tool_budgets (
  task_id VARCHAR(36) NOT NULL,
  tool_kind VARCHAR(40) NOT NULL,
  policy_version INTEGER NOT NULL,
  max_calls INTEGER NOT NULL,
  PRIMARY KEY (task_id, tool_kind),
  CONSTRAINT constraint_3331d CHECK (MAX_CALLS BETWEEN 0 AND 20),
  CONSTRAINT constraint_333 CHECK (POLICY_VERSION = 1),
  CONSTRAINT constraint_3331 CHECK (TOOL_KIND IN('SIMULATED_INSPECTION', 'WORKSPACE_READ', 'WORKSPACE_WRITE'))
);

CREATE TABLE IF NOT EXISTS task_tool_budgets_v2 (
  task_id VARCHAR(36) NOT NULL,
  tool_kind VARCHAR(40) NOT NULL,
  policy_version INTEGER NOT NULL,
  max_calls INTEGER NOT NULL,
  PRIMARY KEY (task_id, tool_kind),
  CONSTRAINT constraint_92e1 CHECK (MAX_CALLS BETWEEN 0 AND 20),
  CONSTRAINT constraint_92 CHECK (POLICY_VERSION = 1),
  CONSTRAINT constraint_92e CHECK (TOOL_KIND IN('SIMULATED_INSPECTION', 'WORKSPACE_READ', 'WORKSPACE_SEARCH', 'WORKSPACE_WRITE'))
);

CREATE TABLE IF NOT EXISTS task_workspace_scopes (
  task_id VARCHAR(36) NOT NULL,
  workspace_id VARCHAR(36) NOT NULL,
  directory_scopes_json TEXT NOT NULL,
  excluded_paths_json TEXT NOT NULL,
  explicit_files_json TEXT NOT NULL,
  effective_files_json TEXT NOT NULL,
  scope_sha256 VARCHAR(64) NOT NULL,
  created_at VARCHAR(40) NOT NULL,
  PRIMARY KEY (task_id)
);

CREATE TABLE IF NOT EXISTS tool_approval_requests (
  id VARCHAR(36) NOT NULL,
  run_id VARCHAR(36) NOT NULL,
  node_id VARCHAR(36) NOT NULL,
  agent_id VARCHAR(36) NOT NULL,
  tool_name VARCHAR(120) NOT NULL,
  tool_version INTEGER NOT NULL,
  original_args_json TEXT NOT NULL,
  edited_args_json TEXT,
  risk_level VARCHAR(20) NOT NULL,
  risk_reason VARCHAR(500) NOT NULL,
  status VARCHAR(40) DEFAULT 'PENDING' NOT NULL,
  decision_action VARCHAR(40),
  decision_reason VARCHAR(500),
  requested_at VARCHAR(40) NOT NULL,
  decided_at VARCHAR(40),
  expires_at VARCHAR(40) NOT NULL,
  PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS tool_attempt_fingerprints (
  id BIGINT GENERATED BY DEFAULT AS IDENTITY NOT NULL,
  run_id VARCHAR(36) NOT NULL,
  tool_id VARCHAR(36) NOT NULL,
  kind VARCHAR(40) NOT NULL,
  fingerprint VARCHAR(64) NOT NULL,
  PRIMARY KEY (id),
  CONSTRAINT constraint_585 UNIQUE (tool_id)
);

CREATE TABLE IF NOT EXISTS tool_call_traces (
  tool_call_id VARCHAR(36) NOT NULL,
  run_id VARCHAR(36) NOT NULL,
  input_args_json TEXT,
  gateway_checks_json TEXT,
  full_result TEXT,
  result_truncated BOOLEAN DEFAULT FALSE NOT NULL,
  created_at VARCHAR(40) NOT NULL,
  PRIMARY KEY (tool_call_id)
);

CREATE TABLE IF NOT EXISTS tool_invocations (
  id VARCHAR(36) NOT NULL,
  run_id VARCHAR(36) NOT NULL,
  agent_id VARCHAR(36) NOT NULL,
  tool_name VARCHAR(80) NOT NULL,
  status VARCHAR(30) NOT NULL,
  result VARCHAR(2000),
  error_message VARCHAR(1000),
  duration_ms BIGINT,
  created_at VARCHAR(40) NOT NULL,
  updated_at VARCHAR(40) NOT NULL,
  tool_version INTEGER,
  resource_version TEXT,
  PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS users (
  id VARCHAR(36) NOT NULL,
  username VARCHAR(32) NOT NULL,
  display_name VARCHAR(120) NOT NULL,
  password_hash VARCHAR(300) NOT NULL,
  role VARCHAR(20) NOT NULL,
  status VARCHAR(20) NOT NULL,
  legacy_claimed BOOLEAN DEFAULT FALSE NOT NULL,
  created_at VARCHAR(40) NOT NULL,
  PRIMARY KEY (id),
  CONSTRAINT constraint_4d4 UNIQUE (username)
);

CREATE TABLE IF NOT EXISTS user_mcp_servers (
  id VARCHAR(36) NOT NULL,
  owner_scope_key VARCHAR(80) NOT NULL,
  name VARCHAR(120) NOT NULL,
  slug VARCHAR(64) NOT NULL,
  transport VARCHAR(30) NOT NULL,
  command VARCHAR(200),
  args_json VARCHAR(2000) DEFAULT '[]' NOT NULL,
  endpoint_url VARCHAR(500),
  headers_cipher TEXT,
  env_cipher TEXT,
  enabled BOOLEAN DEFAULT TRUE NOT NULL,
  created_at VARCHAR(40) NOT NULL,
  updated_at VARCHAR(40) NOT NULL,
  PRIMARY KEY (id),
  CONSTRAINT constraint_644 UNIQUE (owner_scope_key, slug)
);

CREATE TABLE IF NOT EXISTS user_mcp_tools (
  id VARCHAR(36) NOT NULL,
  server_id VARCHAR(36) NOT NULL,
  name VARCHAR(120) NOT NULL,
  description VARCHAR(1000),
  input_schema_json TEXT,
  risk_level VARCHAR(20) DEFAULT 'HIGH' NOT NULL,
  enabled BOOLEAN DEFAULT TRUE NOT NULL,
  created_at VARCHAR(40) NOT NULL,
  updated_at VARCHAR(40) NOT NULL,
  PRIMARY KEY (id),
  CONSTRAINT constraint_b5da UNIQUE (server_id, name)
);

CREATE TABLE IF NOT EXISTS user_model_connections (
  id VARCHAR(36) NOT NULL,
  owner_user_id VARCHAR(36) NOT NULL,
  display_name VARCHAR(80) NOT NULL,
  provider_type VARCHAR(40) NOT NULL,
  base_url VARCHAR(500) NOT NULL,
  model_name VARCHAR(120) NOT NULL,
  encrypted_api_key TEXT NOT NULL,
  key_masked VARCHAR(60) NOT NULL,
  enabled BOOLEAN DEFAULT TRUE NOT NULL,
  is_default BOOLEAN DEFAULT FALSE NOT NULL,
  input_price_per_1m DOUBLE PRECISION,
  output_price_per_1m DOUBLE PRECISION,
  currency VARCHAR(10) DEFAULT 'CNY' NOT NULL,
  last_test_status VARCHAR(20),
  last_test_message VARCHAR(500),
  last_tested_at VARCHAR(40),
  created_at VARCHAR(40) NOT NULL,
  updated_at VARCHAR(40) NOT NULL,
  PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS workbench_schema_migrations (
  migration_id VARCHAR(100) NOT NULL,
  PRIMARY KEY (migration_id)
);

CREATE TABLE IF NOT EXISTS workflow_attempts (
  id VARCHAR(36) NOT NULL,
  node_id VARCHAR(36) NOT NULL,
  attempt_no INTEGER NOT NULL,
  agent_id VARCHAR(36) NOT NULL,
  stop_cause VARCHAR(40),
  revision_index INTEGER DEFAULT 0 NOT NULL,
  critic_verdict_json TEXT,
  PRIMARY KEY (id),
  CONSTRAINT constraint_c67e CHECK (ATTEMPT_NO > 0),
  CONSTRAINT constraint_c67e2 UNIQUE (agent_id),
  CONSTRAINT constraint_c67e27c UNIQUE (node_id, attempt_no)
);

CREATE TABLE IF NOT EXISTS workflow_checkpoints (
  id VARCHAR(36) NOT NULL,
  plan_id VARCHAR(36) NOT NULL,
  version INTEGER NOT NULL,
  completed_nodes INTEGER NOT NULL,
  rounds INTEGER NOT NULL,
  tools INTEGER NOT NULL,
  elapsed_ms BIGINT NOT NULL,
  boundary_sha256 VARCHAR(64) NOT NULL,
  created_at VARCHAR(40) NOT NULL,
  tool_calls INTEGER DEFAULT 0,
  PRIMARY KEY (id),
  CONSTRAINT constraint_9f536f4b_0 CHECK (ELAPSED_MS >= CAST(0 AS BIGINT)),
  CONSTRAINT constraint_9f536f4 CHECK (ROUNDS >= 0),
  CONSTRAINT constraint_9f536 CHECK (VERSION = 1),
  CONSTRAINT constraint_9f536f4b CHECK (TOOLS = 0),
  CONSTRAINT constraint_9f536f CHECK (COMPLETED_NODES >= 0),
  CONSTRAINT constraint_9f536f4b_1 UNIQUE (plan_id, completed_nodes)
);

CREATE TABLE IF NOT EXISTS workflow_checks (
  id VARCHAR(36) NOT NULL,
  node_id VARCHAR(36) NOT NULL,
  attempt_no INTEGER NOT NULL,
  check_id VARCHAR(64) NOT NULL,
  check_version INTEGER NOT NULL,
  status VARCHAR(30) NOT NULL,
  summary VARCHAR(1000),
  evidence VARCHAR(2000),
  inputs_sha256 VARCHAR(64) NOT NULL,
  started_at VARCHAR(40) NOT NULL,
  finished_at VARCHAR(40),
  external_evidence_id VARCHAR(80),
  PRIMARY KEY (id),
  CONSTRAINT constraint_46dc8 CHECK (CHECK_VERSION > 0),
  CONSTRAINT constraint_46dc CHECK (ATTEMPT_NO > 0),
  CONSTRAINT constraint_46dc80 UNIQUE (node_id, attempt_no)
);

CREATE TABLE IF NOT EXISTS workflow_check_inputs (
  check_id VARCHAR(36) NOT NULL,
  dependency_node_id VARCHAR(36) NOT NULL,
  source_kind VARCHAR(30) NOT NULL,
  source_attempt_id VARCHAR(36),
  source_check_id VARCHAR(36),
  source_run_id VARCHAR(36) NOT NULL,
  result_sha256 VARCHAR(64) NOT NULL,
  PRIMARY KEY (check_id, dependency_node_id),
  CONSTRAINT constraint_5aa241a CHECK (((SOURCE_KIND = 'AGENT')
    AND (SOURCE_ATTEMPT_ID IS NOT NULL)
    AND (SOURCE_CHECK_ID IS NULL))
    OR ((SOURCE_KIND = 'SYSTEM_CHECK')
    AND (SOURCE_ATTEMPT_ID IS NULL)
    AND (SOURCE_CHECK_ID IS NOT NULL)))
);

CREATE TABLE IF NOT EXISTS workflow_handoffs (
  receiver_attempt_id VARCHAR(36) NOT NULL,
  dependency_node_id VARCHAR(36) NOT NULL,
  sender_attempt_id VARCHAR(36) NOT NULL,
  schema_version INTEGER NOT NULL,
  accepted_at VARCHAR(40) NOT NULL,
  PRIMARY KEY (receiver_attempt_id, dependency_node_id),
  CONSTRAINT constraint_a645925 CHECK (RECEIVER_ATTEMPT_ID <> SENDER_ATTEMPT_ID),
  CONSTRAINT constraint_a6459 CHECK (SCHEMA_VERSION = 1)
);

CREATE TABLE IF NOT EXISTS workflow_handoff_checks (
  receiver_attempt_id VARCHAR(36) NOT NULL,
  dependency_node_id VARCHAR(36) NOT NULL,
  check_id VARCHAR(36) NOT NULL,
  accepted_at VARCHAR(40) NOT NULL,
  PRIMARY KEY (receiver_attempt_id, dependency_node_id)
);

CREATE TABLE IF NOT EXISTS workflow_handoff_tool_evidence (
  receiver_attempt_id VARCHAR(36) NOT NULL,
  dependency_node_id VARCHAR(36) NOT NULL,
  tool_id VARCHAR(36) NOT NULL,
  tool_kind VARCHAR(40) NOT NULL,
  tool_version INTEGER NOT NULL,
  resource_version TEXT,
  PRIMARY KEY (receiver_attempt_id, dependency_node_id, tool_id),
  CONSTRAINT constraint_263 CHECK (TOOL_VERSION > 0),
  CONSTRAINT constraint_263529 CHECK (TOOL_KIND IN('WORKSPACE_READ', 'WORKSPACE_SEARCH'))
);

CREATE TABLE IF NOT EXISTS workflow_handoff_write_evidence (
  receiver_attempt_id VARCHAR(36) NOT NULL,
  dependency_node_id VARCHAR(36) NOT NULL,
  tool_id VARCHAR(36) NOT NULL,
  tool_version INTEGER NOT NULL,
  resource_version TEXT,
  PRIMARY KEY (receiver_attempt_id, dependency_node_id, tool_id),
  CONSTRAINT constraint_9c3 CHECK (TOOL_VERSION > 0)
);

CREATE TABLE IF NOT EXISTS workflow_plans (
  id VARCHAR(36) NOT NULL,
  run_id VARCHAR(36) NOT NULL,
  plan_version INTEGER NOT NULL,
  template_id VARCHAR(64) NOT NULL,
  template_version INTEGER NOT NULL,
  review_required BOOLEAN NOT NULL,
  approved_at VARCHAR(40),
  graph_sha256 VARCHAR(64),
  source_checkpoint_id VARCHAR(36),
  scope_type VARCHAR(40) DEFAULT 'TEXT_ONLY' NOT NULL,
  authorization_sha256 VARCHAR(64),
  max_attempts INTEGER DEFAULT 1 NOT NULL,
  PRIMARY KEY (id),
  CONSTRAINT constraint_f27e01ca_1 CHECK (REVIEW_REQUIRED
    OR (APPROVED_AT IS NOT NULL)),
  CONSTRAINT constraint_f27e01 CHECK (PLAN_VERSION = 1),
  CONSTRAINT constraint_f27e01c CHECK (TEMPLATE_VERSION > 0),
  CONSTRAINT constraint_f27e01ca CHECK (MAX_ATTEMPTS BETWEEN 1 AND 2),
  CONSTRAINT workflow_plan_scope_v3 CHECK (((SCOPE_TYPE IN('WORKSPACE_READ_ONLY', 'WORKSPACE_WRITE_AUTHORIZED', 'WORKSPACE_BUG_FIX'))
    AND (AUTHORIZATION_SHA256 IS NOT NULL))
    OR ((SCOPE_TYPE = 'TEXT_ONLY')
    AND (AUTHORIZATION_SHA256 IS NULL))),
  CONSTRAINT constraint_f27e UNIQUE (run_id),
  CONSTRAINT constraint_f27e01ca_0 UNIQUE (id, run_id)
);

CREATE TABLE IF NOT EXISTS workflow_plan_controls (
  run_id VARCHAR(36) NOT NULL,
  execution_mode VARCHAR(30) DEFAULT 'STEP_TEACHING' NOT NULL,
  breakpoints_json TEXT DEFAULT '[]' NOT NULL,
  updated_at VARCHAR(40) NOT NULL,
  PRIMARY KEY (run_id)
);

CREATE TABLE IF NOT EXISTS workspaces (
  id VARCHAR(36) NOT NULL,
  repository_path VARCHAR(2000) NOT NULL,
  workspace_path VARCHAR(2000) NOT NULL,
  base_branch VARCHAR(500) NOT NULL,
  base_commit VARCHAR(64) NOT NULL,
  branch_name VARCHAR(500) NOT NULL,
  created_at VARCHAR(40) NOT NULL,
  PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS workspace_declared_files (
  workspace_id VARCHAR(36) NOT NULL,
  path VARCHAR(200) NOT NULL,
  sha256 VARCHAR(64) NOT NULL,
  source VARCHAR(30) NOT NULL,
  created_at VARCHAR(40) NOT NULL,
  PRIMARY KEY (workspace_id, path)
);


ALTER TABLE agent_assemblies ADD CONSTRAINT constraint_15c FOREIGN KEY (agent_id) REFERENCES agent_invocations (id);






















ALTER TABLE eval_custom_samples ADD CONSTRAINT fk_eval_custom_sample_dataset FOREIGN KEY (dataset_id) REFERENCES eval_custom_datasets (id) ON DELETE CASCADE;

ALTER TABLE eval_lab_case_results ADD CONSTRAINT fk_eval_lab_case_run FOREIGN KEY (eval_run_id) REFERENCES eval_lab_runs (id) ON DELETE CASCADE;



















ALTER TABLE plan_nodes ADD CONSTRAINT fk_plan_nodes_plan_version_id FOREIGN KEY (plan_version_id) REFERENCES workflow_plans (id);
ALTER TABLE plan_nodes ADD CONSTRAINT workflow_node_run FOREIGN KEY (plan_version_id, run_id) REFERENCES workflow_plans (id, run_id);


ALTER TABLE plan_node_dependencies ADD CONSTRAINT constraint_4cf FOREIGN KEY (node_id) REFERENCES plan_nodes (id);
ALTER TABLE plan_node_dependencies ADD CONSTRAINT constraint_4cf1 FOREIGN KEY (prerequisite_id) REFERENCES plan_nodes (id);

ALTER TABLE plan_node_specs ADD CONSTRAINT constraint_fad FOREIGN KEY (plan_node_id) REFERENCES plan_nodes (id);

































ALTER TABLE user_mcp_tools ADD CONSTRAINT constraint_b5d FOREIGN KEY (server_id) REFERENCES user_mcp_servers (id);



ALTER TABLE workflow_attempts ADD CONSTRAINT constraint_c67e27 FOREIGN KEY (agent_id) REFERENCES agent_invocations (id);
ALTER TABLE workflow_attempts ADD CONSTRAINT constraint_c67 FOREIGN KEY (node_id) REFERENCES plan_nodes (id);

ALTER TABLE workflow_checkpoints ADD CONSTRAINT constraint_9f53 FOREIGN KEY (plan_id) REFERENCES workflow_plans (id);

ALTER TABLE workflow_checks ADD CONSTRAINT constraint_46d FOREIGN KEY (node_id) REFERENCES plan_nodes (id);

ALTER TABLE workflow_check_inputs ADD CONSTRAINT constraint_5aa FOREIGN KEY (dependency_node_id) REFERENCES plan_nodes (id);
ALTER TABLE workflow_check_inputs ADD CONSTRAINT constraint_5aa2 FOREIGN KEY (source_attempt_id) REFERENCES workflow_attempts (id);
ALTER TABLE workflow_check_inputs ADD CONSTRAINT constraint_5a FOREIGN KEY (check_id) REFERENCES workflow_checks (id);
ALTER TABLE workflow_check_inputs ADD CONSTRAINT constraint_5aa24 FOREIGN KEY (source_check_id) REFERENCES workflow_checks (id);

ALTER TABLE workflow_handoffs ADD CONSTRAINT constraint_a64 FOREIGN KEY (dependency_node_id) REFERENCES plan_nodes (id);
ALTER TABLE workflow_handoffs ADD CONSTRAINT constraint_a6 FOREIGN KEY (receiver_attempt_id) REFERENCES workflow_attempts (id);
ALTER TABLE workflow_handoffs ADD CONSTRAINT constraint_a645 FOREIGN KEY (sender_attempt_id) REFERENCES workflow_attempts (id);

ALTER TABLE workflow_handoff_checks ADD CONSTRAINT constraint_842 FOREIGN KEY (dependency_node_id) REFERENCES plan_nodes (id);
ALTER TABLE workflow_handoff_checks ADD CONSTRAINT constraint_84 FOREIGN KEY (receiver_attempt_id) REFERENCES workflow_attempts (id);
ALTER TABLE workflow_handoff_checks ADD CONSTRAINT constraint_842b FOREIGN KEY (check_id) REFERENCES workflow_checks (id);

ALTER TABLE workflow_handoff_tool_evidence ADD CONSTRAINT constraint_26 FOREIGN KEY (tool_id) REFERENCES tool_invocations (id);
ALTER TABLE workflow_handoff_tool_evidence ADD CONSTRAINT constraint_26352 FOREIGN KEY (receiver_attempt_id, dependency_node_id) REFERENCES workflow_handoffs (receiver_attempt_id, dependency_node_id);

ALTER TABLE workflow_handoff_write_evidence ADD CONSTRAINT constraint_9c FOREIGN KEY (tool_id) REFERENCES tool_invocations (id);
ALTER TABLE workflow_handoff_write_evidence ADD CONSTRAINT constraint_9c3f2 FOREIGN KEY (receiver_attempt_id, dependency_node_id) REFERENCES workflow_handoffs (receiver_attempt_id, dependency_node_id);

ALTER TABLE workflow_plans ADD CONSTRAINT constraint_f27e0 FOREIGN KEY (run_id) REFERENCES runs (id);
ALTER TABLE workflow_plans ADD CONSTRAINT fk_workflow_plans_source_checkpoint_id FOREIGN KEY (source_checkpoint_id) REFERENCES workflow_checkpoints (id);




-- 结构化索引（显式 idx_*；约束自带索引已由约束创建）。
CREATE INDEX IF NOT EXISTS agentscope_sessions_session_idx ON agentscope_sessions (session_id);











CREATE INDEX IF NOT EXISTS idx_bug_intakes_run ON bug_intakes (run_id);
CREATE INDEX IF NOT EXISTS idx_bug_intakes_source ON bug_intakes (bug_id, source, source_version, workspace_id, created_at);


CREATE INDEX IF NOT EXISTS idx_context_artifacts_run ON context_artifacts (run_id, created_at);

CREATE INDEX IF NOT EXISTS idx_cost_reservations_run ON cost_reservations (run_id);







CREATE INDEX IF NOT EXISTS idx_eval_benchmark_packs_category ON eval_benchmark_packs (category, status);


CREATE INDEX IF NOT EXISTS fk_eval_custom_sample_dataset_index_c ON eval_custom_samples (dataset_id);
CREATE INDEX IF NOT EXISTS idx_eval_custom_samples_dataset ON eval_custom_samples (dataset_id);

CREATE INDEX IF NOT EXISTS fk_eval_lab_case_run_index_3 ON eval_lab_case_results (eval_run_id);
CREATE INDEX IF NOT EXISTS idx_eval_lab_cases_run_category ON eval_lab_case_results (eval_run_id, category);
CREATE INDEX IF NOT EXISTS idx_eval_lab_cases_sample ON eval_lab_case_results (eval_run_id, sample_id, repeat_index);

CREATE INDEX IF NOT EXISTS idx_eval_lab_runs_owner_created ON eval_lab_runs (owner_scope_key, created_at);
CREATE INDEX IF NOT EXISTS idx_eval_lab_runs_status ON eval_lab_runs (status);

CREATE INDEX IF NOT EXISTS events_run_seq_idx ON events (run_id, run_seq);

CREATE INDEX IF NOT EXISTS idx_external_actions_run ON external_actions (run_id);

CREATE INDEX IF NOT EXISTS idx_external_action_checks_action ON external_action_checks (action_id);





CREATE INDEX IF NOT EXISTS idx_knowledge_chunks_document ON knowledge_chunks (document_id, chunk_index);
CREATE INDEX IF NOT EXISTS idx_knowledge_chunks_embedding_model ON knowledge_chunks (workspace_id, embedding_model);
CREATE INDEX IF NOT EXISTS idx_knowledge_chunks_workspace ON knowledge_chunks (workspace_id);

CREATE UNIQUE INDEX IF NOT EXISTS uq_knowledge_documents_source ON knowledge_documents (workspace_id, source_path, content_sha256);
CREATE INDEX IF NOT EXISTS idx_knowledge_documents_workspace ON knowledge_documents (workspace_id, status);




CREATE INDEX IF NOT EXISTS idx_memory_records_embedding_model ON memory_records (workspace_id, status, embedding_model);
CREATE INDEX IF NOT EXISTS idx_memory_records_source_run ON memory_records (source_run_id);
CREATE INDEX IF NOT EXISTS idx_memory_records_workspace_status ON memory_records (workspace_id, status, memory_type);

CREATE INDEX IF NOT EXISTS idx_model_call_payloads_run ON model_call_payloads (run_id);



CREATE INDEX IF NOT EXISTS plan_candidates_group ON plan_candidates (group_id, input_version);

CREATE UNIQUE INDEX IF NOT EXISTS workflow_node_key_index_8 ON plan_nodes (plan_version_id, node_key);
CREATE INDEX IF NOT EXISTS fk_plan_nodes_plan_version_id_index_8 ON plan_nodes (plan_version_id);
CREATE INDEX IF NOT EXISTS workflow_node_run_index_8 ON plan_nodes (plan_version_id, run_id);

CREATE INDEX IF NOT EXISTS idx_plan_node_binding_run ON plan_node_agent_bindings (run_id);



CREATE INDEX IF NOT EXISTS idx_plan_revision_edges_run ON plan_revision_edges (run_id);
CREATE INDEX IF NOT EXISTS idx_plan_revision_edges_target ON plan_revision_edges (run_id, target_node_id);

CREATE INDEX IF NOT EXISTS idx_private_agents_owner ON private_agents (owner_scope_key);


CREATE INDEX IF NOT EXISTS idx_private_skills_owner ON private_skills (owner_scope_key);


CREATE INDEX IF NOT EXISTS idx_private_tools_owner ON private_tools (owner_scope_key);




CREATE INDEX IF NOT EXISTS idx_repository_auth_owner ON repository_authorizations (owner_scope_key);






CREATE UNIQUE INDEX IF NOT EXISTS constraint_1aa_index_2 ON run_memory_references (run_id, memory_id);











CREATE INDEX IF NOT EXISTS idx_tool_approval_requests_node ON tool_approval_requests (run_id, node_id);
CREATE INDEX IF NOT EXISTS idx_tool_approval_requests_run ON tool_approval_requests (run_id, status);


CREATE INDEX IF NOT EXISTS idx_tool_call_traces_run ON tool_call_traces (run_id);



CREATE INDEX IF NOT EXISTS idx_user_mcp_servers_owner ON user_mcp_servers (owner_scope_key, enabled);

CREATE INDEX IF NOT EXISTS idx_user_mcp_tools_server ON user_mcp_tools (server_id, enabled);

CREATE INDEX IF NOT EXISTS idx_user_model_connections_owner ON user_model_connections (owner_user_id);


CREATE UNIQUE INDEX IF NOT EXISTS constraint_c67e27c_index_e ON workflow_attempts (node_id, attempt_no);
CREATE UNIQUE INDEX IF NOT EXISTS constraint_c67e2_index_e ON workflow_attempts (agent_id);
CREATE INDEX IF NOT EXISTS constraint_c67_index_e ON workflow_attempts (node_id);








CREATE INDEX IF NOT EXISTS fk_workflow_plans_source_checkpoint_id_index_f ON workflow_plans (source_checkpoint_id);




