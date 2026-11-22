-- Phase 1：真并行调度、有界反思环与四级上下文压缩的持久化结构。
-- 只新增本阶段对象，不修改历史 V1~V6。

-- 反思回边：审查节点回指直接上游目标，revision_count 持久化已触发的次数。
CREATE TABLE IF NOT EXISTS plan_revision_edges (
  id VARCHAR(36) PRIMARY KEY,
  run_id VARCHAR(36) NOT NULL,
  reviewer_node_id VARCHAR(36) NOT NULL,
  target_node_id VARCHAR(36) NOT NULL,
  max_revisions INT DEFAULT 2 NOT NULL CHECK(max_revisions BETWEEN 1 AND 5),
  revision_count INT DEFAULT 0 NOT NULL CHECK(revision_count >= 0),
  last_verdict_json CLOB,
  created_at VARCHAR(40) NOT NULL,
  updated_at VARCHAR(40) NOT NULL,
  UNIQUE(run_id, reviewer_node_id)
);

CREATE INDEX IF NOT EXISTS idx_plan_revision_edges_run ON plan_revision_edges(run_id);
CREATE INDEX IF NOT EXISTS idx_plan_revision_edges_target ON plan_revision_edges(run_id, target_node_id);

-- 节点尝试记录反思轮次与最近一次结构化评审结论。
ALTER TABLE workflow_attempts ADD COLUMN IF NOT EXISTS revision_index INT DEFAULT 0 NOT NULL;
ALTER TABLE workflow_attempts ADD COLUMN IF NOT EXISTS critic_verdict_json CLOB;

-- 模型调用持久化上下文分桶与压缩等级，供玻璃盒占比条展示。
ALTER TABLE model_call_payloads ADD COLUMN IF NOT EXISTS context_breakdown_json CLOB;
ALTER TABLE model_call_payloads ADD COLUMN IF NOT EXISTS compaction_tier VARCHAR(30);
