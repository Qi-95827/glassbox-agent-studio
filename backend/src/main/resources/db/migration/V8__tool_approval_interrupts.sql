-- Phase 2：工具级动态审批单。原始参数与用户修改后的参数都持久化，拒绝理由可回传模型。
CREATE TABLE IF NOT EXISTS tool_approval_requests (
  id VARCHAR(36) PRIMARY KEY,
  run_id VARCHAR(36) NOT NULL,
  node_id VARCHAR(36) NOT NULL,
  agent_id VARCHAR(36) NOT NULL,
  tool_name VARCHAR(120) NOT NULL,
  tool_version INT NOT NULL,
  original_args_json CLOB NOT NULL,
  edited_args_json CLOB,
  risk_level VARCHAR(20) NOT NULL,
  risk_reason VARCHAR(500) NOT NULL,
  status VARCHAR(40) DEFAULT 'PENDING' NOT NULL,
  decision_action VARCHAR(40),
  decision_reason VARCHAR(500),
  requested_at VARCHAR(40) NOT NULL,
  decided_at VARCHAR(40),
  expires_at VARCHAR(40) NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_tool_approval_requests_run ON tool_approval_requests(run_id, status);
CREATE INDEX IF NOT EXISTS idx_tool_approval_requests_node ON tool_approval_requests(run_id, node_id);
