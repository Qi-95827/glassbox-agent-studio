-- 旧库补丁迁移：V1 已用 CREATE TABLE IF NOT EXISTS 覆盖缺失表；
-- 本脚本补齐历史库缺失的列、约束、索引与一次性数据回填。
-- 全部语句幂等，新库执行时为空操作。

ALTER TABLE tasks ADD COLUMN IF NOT EXISTS archived_at VARCHAR(40);
ALTER TABLE tasks ADD COLUMN IF NOT EXISTS model_mode VARCHAR(20) DEFAULT 'mock' NOT NULL;
ALTER TABLE tasks ADD COLUMN IF NOT EXISTS workspace_id VARCHAR(36);
ALTER TABLE tasks ADD COLUMN IF NOT EXISTS file_scope VARCHAR(2000);
ALTER TABLE tasks ADD COLUMN IF NOT EXISTS write_enabled BOOLEAN DEFAULT FALSE NOT NULL;
ALTER TABLE tasks ADD COLUMN IF NOT EXISTS bug_id VARCHAR(80);
ALTER TABLE tasks ADD COLUMN IF NOT EXISTS memory_enabled BOOLEAN DEFAULT FALSE NOT NULL;
ALTER TABLE tasks ADD COLUMN IF NOT EXISTS constraints_json VARCHAR(20000) DEFAULT '[]' NOT NULL;
ALTER TABLE tasks ADD COLUMN IF NOT EXISTS acceptance_json VARCHAR(20000) DEFAULT '[]' NOT NULL;
ALTER TABLE tasks ADD COLUMN IF NOT EXISTS entry_kind VARCHAR(30) DEFAULT 'GENERAL' NOT NULL;
ALTER TABLE tasks ADD COLUMN IF NOT EXISTS file_scopes_json VARCHAR(20000) DEFAULT '[]' NOT NULL;
ALTER TABLE runs ADD COLUMN IF NOT EXISTS max_per_tool INT DEFAULT 20 NOT NULL;
ALTER TABLE runs ALTER COLUMN max_rounds DROP NOT NULL;
ALTER TABLE runs ALTER COLUMN max_tools DROP NOT NULL;
ALTER TABLE runs ALTER COLUMN max_seconds DROP NOT NULL;
ALTER TABLE runs ALTER COLUMN max_per_tool DROP NOT NULL;
ALTER TABLE runs ALTER COLUMN max_per_tool DROP DEFAULT;
ALTER TABLE runs ADD COLUMN IF NOT EXISTS max_reported_tokens BIGINT;
ALTER TABLE runs ADD COLUMN IF NOT EXISTS max_identical_tools INT;
ALTER TABLE runs ADD COLUMN IF NOT EXISTS max_agent_rounds INT;
ALTER TABLE runs ADD COLUMN IF NOT EXISTS max_agent_tools INT;
ALTER TABLE runs ADD COLUMN IF NOT EXISTS recovery_dispatch VARCHAR(30) DEFAULT 'UNKNOWN' NOT NULL;
ALTER TABLE runs ADD COLUMN IF NOT EXISTS stop_cause VARCHAR(40);
ALTER TABLE events ADD COLUMN IF NOT EXISTS reason_code VARCHAR(50);
ALTER TABLE events ADD COLUMN IF NOT EXISTS plan_version_id VARCHAR(36);
ALTER TABLE events ADD COLUMN IF NOT EXISTS node_id VARCHAR(36);
ALTER TABLE events ADD COLUMN IF NOT EXISTS attempt_id VARCHAR(36);
ALTER TABLE events ADD COLUMN IF NOT EXISTS agent_id VARCHAR(36);
ALTER TABLE events ADD COLUMN IF NOT EXISTS check_id VARCHAR(36);
ALTER TABLE events ADD COLUMN IF NOT EXISTS model_call_id VARCHAR(36);
ALTER TABLE events ADD COLUMN IF NOT EXISTS tool_call_id VARCHAR(36);
ALTER TABLE events ADD COLUMN IF NOT EXISTS run_seq BIGINT;
ALTER TABLE checkpoints ADD COLUMN IF NOT EXISTS checksum VARCHAR(64);
ALTER TABLE checkpoints ADD COLUMN IF NOT EXISTS engine VARCHAR(30) DEFAULT 'LEGACY' NOT NULL;
ALTER TABLE agent_invocations ADD COLUMN IF NOT EXISTS role_code VARCHAR(30);
ALTER TABLE agent_invocations ADD COLUMN IF NOT EXISTS role_version INT;
ALTER TABLE agent_invocations ADD COLUMN IF NOT EXISTS definition_id VARCHAR(64);
ALTER TABLE agent_invocations ADD COLUMN IF NOT EXISTS definition_version INT;
ALTER TABLE agent_invocations ADD COLUMN IF NOT EXISTS definition_sha256 VARCHAR(64);
ALTER TABLE agent_tool_grants ADD COLUMN IF NOT EXISTS tool_version INT;
ALTER TABLE plan_nodes ADD COLUMN IF NOT EXISTS source_agent_id VARCHAR(36);
ALTER TABLE plan_nodes ADD COLUMN IF NOT EXISTS definition_id VARCHAR(64);
ALTER TABLE plan_nodes ADD COLUMN IF NOT EXISTS definition_version INT;
ALTER TABLE plan_nodes ADD COLUMN IF NOT EXISTS definition_sha256 VARCHAR(64);
ALTER TABLE plan_nodes ADD COLUMN IF NOT EXISTS plan_version_id VARCHAR(36) REFERENCES workflow_plans(id);
ALTER TABLE plan_nodes ADD COLUMN IF NOT EXISTS node_key VARCHAR(64);
ALTER TABLE plan_nodes ADD COLUMN IF NOT EXISTS node_label VARCHAR(120);
ALTER TABLE plan_nodes ADD COLUMN IF NOT EXISTS phase_key VARCHAR(64);
ALTER TABLE plan_nodes ADD COLUMN IF NOT EXISTS permission_role VARCHAR(30);
ALTER TABLE plan_nodes ALTER COLUMN role_code DROP NOT NULL;
ALTER TABLE plan_nodes ADD CONSTRAINT IF NOT EXISTS workflow_node_key UNIQUE(plan_version_id,node_key);
ALTER TABLE plan_nodes ADD CONSTRAINT IF NOT EXISTS workflow_node_run FOREIGN KEY(plan_version_id,run_id) REFERENCES workflow_plans(id,run_id);
ALTER TABLE workflow_attempts ADD COLUMN IF NOT EXISTS stop_cause VARCHAR(40);
ALTER TABLE workflow_plans ADD COLUMN IF NOT EXISTS source_checkpoint_id VARCHAR(36) REFERENCES workflow_checkpoints(id);
ALTER TABLE workflow_plans ADD COLUMN IF NOT EXISTS scope_type VARCHAR(40) DEFAULT 'TEXT_ONLY' NOT NULL;
ALTER TABLE workflow_plans ADD COLUMN IF NOT EXISTS authorization_sha256 VARCHAR(64);
ALTER TABLE workflow_plans ADD COLUMN IF NOT EXISTS max_attempts INT DEFAULT 1 NOT NULL CHECK(max_attempts BETWEEN 1 AND 2);
ALTER TABLE workflow_plans DROP CONSTRAINT IF EXISTS workflow_plan_scope;
ALTER TABLE workflow_plans DROP CONSTRAINT IF EXISTS workflow_plan_scope_v2;
ALTER TABLE workflow_plans ADD CONSTRAINT IF NOT EXISTS workflow_plan_scope_v3
  CHECK((scope_type='TEXT_ONLY' AND authorization_sha256 IS NULL)
    OR (scope_type IN ('WORKSPACE_READ_ONLY','WORKSPACE_WRITE_AUTHORIZED','WORKSPACE_BUG_FIX') AND authorization_sha256 IS NOT NULL));
ALTER TABLE plan_nodes ADD COLUMN IF NOT EXISTS workspace_read BOOLEAN DEFAULT FALSE NOT NULL;
ALTER TABLE plan_nodes ADD COLUMN IF NOT EXISTS workspace_write BOOLEAN DEFAULT FALSE NOT NULL;
ALTER TABLE plan_nodes ADD CONSTRAINT IF NOT EXISTS workflow_workspace_write
  CHECK(NOT workspace_write OR (workspace_read AND plan_version_id IS NOT NULL));
ALTER TABLE plan_nodes ADD COLUMN IF NOT EXISTS max_node_model_calls INT;
ALTER TABLE plan_nodes ADD COLUMN IF NOT EXISTS max_node_tool_calls INT;
ALTER TABLE plan_nodes ADD CONSTRAINT IF NOT EXISTS workflow_node_budget
  CHECK((max_node_model_calls IS NULL OR (plan_version_id IS NOT NULL AND max_node_model_calls BETWEEN 1 AND 20))
    AND (max_node_tool_calls IS NULL OR (plan_version_id IS NOT NULL AND max_node_tool_calls BETWEEN 0 AND 20)));
ALTER TABLE plan_nodes ADD COLUMN IF NOT EXISTS node_kind VARCHAR(30) DEFAULT 'AGENT' NOT NULL;
ALTER TABLE plan_nodes ADD COLUMN IF NOT EXISTS check_id VARCHAR(64);
ALTER TABLE plan_nodes ADD COLUMN IF NOT EXISTS check_version INT;
ALTER TABLE plan_nodes DROP CONSTRAINT IF EXISTS plan_node_shape;
ALTER TABLE plan_nodes ADD CONSTRAINT IF NOT EXISTS plan_node_shape_v2 CHECK(
    (plan_version_id IS NULL AND node_kind='AGENT' AND role_code IS NOT NULL AND permission_role IS NULL
      AND node_key IS NULL AND node_label IS NULL AND phase_key IS NULL)
    OR (plan_version_id IS NOT NULL AND node_kind='AGENT' AND role_code IS NULL AND prerequisite_role IS NULL
      AND permission_role IS NOT NULL AND node_key IS NOT NULL AND node_label IS NOT NULL AND phase_key IS NOT NULL
      AND definition_id IS NOT NULL AND definition_version IS NOT NULL AND definition_sha256 IS NOT NULL
      AND check_id IS NULL AND check_version IS NULL)
    OR (plan_version_id IS NOT NULL AND node_kind='SYSTEM_CHECK' AND role_code IS NULL AND prerequisite_role IS NULL
      AND permission_role IS NULL AND node_key IS NOT NULL AND node_label IS NOT NULL AND phase_key IS NOT NULL
      AND definition_id IS NULL AND definition_version IS NULL AND definition_sha256 IS NULL
      AND check_id IS NOT NULL AND check_version IS NOT NULL));
ALTER TABLE workflow_checks ADD COLUMN IF NOT EXISTS external_evidence_id VARCHAR(80);
ALTER TABLE tool_invocations ADD COLUMN IF NOT EXISTS tool_version INT;
ALTER TABLE tool_invocations ADD COLUMN IF NOT EXISTS resource_version CLOB;
ALTER TABLE workflow_checkpoints ADD COLUMN IF NOT EXISTS tool_calls INT DEFAULT 0;
ALTER TABLE workflow_handoff_tool_evidence ADD COLUMN IF NOT EXISTS resource_version CLOB;
ALTER TABLE workflow_handoff_write_evidence ADD COLUMN IF NOT EXISTS resource_version CLOB;
ALTER TABLE tasks ADD COLUMN IF NOT EXISTS version INT DEFAULT 1 NOT NULL;
ALTER TABLE bug_checks ADD COLUMN IF NOT EXISTS workspace_fingerprint VARCHAR(64);
ALTER TABLE bug_checks ADD COLUMN IF NOT EXISTS run_id VARCHAR(36);
ALTER TABLE evaluation_batches ADD COLUMN IF NOT EXISTS definition_json CLOB;
ALTER TABLE evaluation_batches ADD COLUMN IF NOT EXISTS definition_sha256 VARCHAR(64);
ALTER TABLE evaluation_batches ADD COLUMN IF NOT EXISTS suite_version INT;
ALTER TABLE evaluation_batches ADD COLUMN IF NOT EXISTS config_json CLOB;
ALTER TABLE evaluation_batches ADD COLUMN IF NOT EXISTS config_sha256 VARCHAR(64);
ALTER TABLE evaluation_batches ADD COLUMN IF NOT EXISTS requested_by VARCHAR(80);
ALTER TABLE git_actions ADD COLUMN IF NOT EXISTS target VARCHAR(300);
ALTER TABLE git_actions ADD COLUMN IF NOT EXISTS detail VARCHAR(1000);
ALTER TABLE git_delivery_requests ADD COLUMN IF NOT EXISTS remote_url_sha256 VARCHAR(64);
ALTER TABLE git_delivery_requests ADD COLUMN IF NOT EXISTS target_commit VARCHAR(64);
ALTER TABLE tasks ADD COLUMN IF NOT EXISTS max_cost_micros BIGINT;
ALTER TABLE task_source_artifacts ADD COLUMN IF NOT EXISTS content CLOB;
ALTER TABLE task_source_artifacts ADD COLUMN IF NOT EXISTS file_versions_json CLOB;
ALTER TABLE plan_candidates ADD COLUMN IF NOT EXISTS workspace_fingerprint VARCHAR(64);
ALTER TABLE plan_candidates ADD COLUMN IF NOT EXISTS file_versions_json CLOB;
ALTER TABLE plan_candidates ADD COLUMN IF NOT EXISTS prepared_workspace_id VARCHAR(36);
ALTER TABLE plan_candidates ADD COLUMN IF NOT EXISTS preparation_status VARCHAR(20);
ALTER TABLE plan_candidates ADD COLUMN IF NOT EXISTS preparation_id VARCHAR(36);
ALTER TABLE plan_candidates ADD COLUMN IF NOT EXISTS workspace_operation_id VARCHAR(36);
ALTER TABLE plan_candidates ADD COLUMN IF NOT EXISTS preparation_started_at VARCHAR(40);
ALTER TABLE plan_candidates ADD COLUMN IF NOT EXISTS agent_bindings_json CLOB;
ALTER TABLE plan_candidates ADD COLUMN IF NOT EXISTS agent_aliases_json CLOB;
ALTER TABLE plan_candidates ADD COLUMN IF NOT EXISTS run_id VARCHAR(36);
ALTER TABLE cost_reservations ADD COLUMN IF NOT EXISTS over_budget_micros BIGINT DEFAULT 0 NOT NULL;
ALTER TABLE run_cost_limits ADD COLUMN IF NOT EXISTS overage_micros BIGINT DEFAULT 0 NOT NULL;
ALTER TABLE private_agents ADD COLUMN IF NOT EXISTS model_strategy VARCHAR(20) DEFAULT 'INHERIT_TASK' NOT NULL;
ALTER TABLE private_agent_versions ADD COLUMN IF NOT EXISTS model_strategy VARCHAR(20) DEFAULT 'INHERIT_TASK' NOT NULL;
ALTER TABLE cost_reservations ADD COLUMN IF NOT EXISTS price_version_id VARCHAR(36);

INSERT INTO task_tool_budgets_v2(task_id,tool_kind,policy_version,max_calls) SELECT old.task_id,old.tool_kind,old.policy_version,old.max_calls FROM task_tool_budgets old WHERE NOT EXISTS (SELECT 1 FROM workbench_schema_migrations WHERE migration_id='tool_budgets_v2') AND NOT EXISTS (SELECT 1 FROM task_tool_budgets_v2 newer WHERE newer.task_id=old.task_id AND newer.tool_kind=old.tool_kind);
INSERT INTO workbench_schema_migrations(migration_id) SELECT 'tool_budgets_v2' WHERE NOT EXISTS (SELECT 1 FROM workbench_schema_migrations WHERE migration_id='tool_budgets_v2');
INSERT INTO plan_node_dependencies(run_id,node_id,prerequisite_id) SELECT child.run_id,child.id,parent.id FROM plan_nodes child JOIN plan_nodes parent ON parent.run_id=child.run_id AND parent.role_code=child.prerequisite_role WHERE child.prerequisite_role IS NOT NULL AND child.id<>parent.id AND NOT EXISTS (SELECT 1 FROM plan_node_dependencies existing WHERE existing.run_id=child.run_id AND existing.node_id=child.id AND existing.prerequisite_id=parent.id);
INSERT INTO workbench_schema_migrations(migration_id) SELECT 'workflow_check_v1' WHERE NOT EXISTS (SELECT 1 FROM workbench_schema_migrations WHERE migration_id='workflow_check_v1');
INSERT INTO workbench_schema_migrations(migration_id) SELECT 'workflow_plan_v1' WHERE NOT EXISTS (SELECT 1 FROM workbench_schema_migrations WHERE migration_id='workflow_plan_v1');

CREATE INDEX IF NOT EXISTS events_run_seq_idx ON events(run_id, run_seq);
CREATE INDEX IF NOT EXISTS idx_bug_intakes_run ON bug_intakes(run_id);
CREATE INDEX IF NOT EXISTS idx_bug_intakes_source ON bug_intakes(bug_id,source,source_version,workspace_id,created_at);
CREATE INDEX IF NOT EXISTS agentscope_sessions_session_idx ON agentscope_sessions (session_id);
CREATE INDEX IF NOT EXISTS plan_candidates_group ON plan_candidates(group_id, input_version);
CREATE INDEX IF NOT EXISTS idx_cost_reservations_run ON cost_reservations(run_id);
CREATE INDEX IF NOT EXISTS idx_external_actions_run ON external_actions(run_id);
CREATE INDEX IF NOT EXISTS idx_external_action_checks_action ON external_action_checks(action_id);
CREATE INDEX IF NOT EXISTS idx_private_skills_owner ON private_skills(owner_scope_key);
CREATE INDEX IF NOT EXISTS idx_private_tools_owner ON private_tools(owner_scope_key);
CREATE INDEX IF NOT EXISTS idx_private_agents_owner ON private_agents(owner_scope_key);
CREATE INDEX IF NOT EXISTS idx_plan_node_binding_run ON plan_node_agent_bindings(run_id);
CREATE INDEX IF NOT EXISTS idx_repository_auth_owner ON repository_authorizations(owner_scope_key);
