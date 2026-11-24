-- 核心高频查询的二级索引（H2 档）。
--
-- 背景：任务列表、运行详情、事件回放、调用明细与审计统计都以 run_id / task_id / status 为主过滤条件，
-- 但此前这些表只有主键，列表页与会话回放会退化为全表扫描。
-- 只新增索引，不改任何列或约束；与 PostgreSQL 的 V4 保持等价。

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
