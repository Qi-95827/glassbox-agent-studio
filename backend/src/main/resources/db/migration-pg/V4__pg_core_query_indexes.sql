-- 核心高频查询的二级索引（PostgreSQL 档），与 H2 的 V14 等价。
--
-- 只新增索引，不改任何列或约束。使用 IF NOT EXISTS 保证对既有库重复执行安全。

CREATE INDEX IF NOT EXISTS idx_runs_task_created ON runs(task_id, created_at);
CREATE INDEX IF NOT EXISTS idx_runs_status_updated ON runs(status, updated_at);
CREATE INDEX IF NOT EXISTS idx_runs_parent ON runs(parent_id);

CREATE INDEX IF NOT EXISTS idx_agent_invocations_run_status ON agent_invocations(run_id, status);
CREATE INDEX IF NOT EXISTS idx_model_invocations_run_status ON model_invocations(run_id, status);
CREATE INDEX IF NOT EXISTS idx_tool_invocations_run_status ON tool_invocations(run_id, status);

CREATE INDEX IF NOT EXISTS idx_events_run_type ON events(run_id, event_type);

CREATE INDEX IF NOT EXISTS idx_tasks_created ON tasks(created_at);
CREATE INDEX IF NOT EXISTS idx_tasks_workspace ON tasks(workspace_id);

CREATE INDEX IF NOT EXISTS idx_resource_ownership_owner ON resource_ownership(resource_type, owner_scope_key);

CREATE INDEX IF NOT EXISTS idx_tool_approval_requests_run_status ON tool_approval_requests(run_id, status);

CREATE INDEX IF NOT EXISTS idx_eval_lab_runs_owner_status ON eval_lab_runs(owner_scope_key, status);
