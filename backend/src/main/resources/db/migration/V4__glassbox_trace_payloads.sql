-- 玻璃盒全链路透明透视附表：独立存储模型 I/O 快照、ToolGateway 安检轨迹与节点断点配置。
-- 全部采用 1:1 附表，保持核心调用列表轻量并 100% 向后兼容历史数据。

CREATE TABLE IF NOT EXISTS model_call_payloads (
    model_call_id VARCHAR(36) PRIMARY KEY,
    run_id VARCHAR(36) NOT NULL,
    request_messages_json CLOB,
    request_tools_json CLOB,
    response_content CLOB,
    response_tool_calls_json CLOB,
    truncated BOOLEAN DEFAULT FALSE NOT NULL,
    created_at VARCHAR(40) NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_model_call_payloads_run ON model_call_payloads(run_id);

CREATE TABLE IF NOT EXISTS tool_call_traces (
    tool_call_id VARCHAR(36) PRIMARY KEY,
    run_id VARCHAR(36) NOT NULL,
    input_args_json CLOB,
    gateway_checks_json CLOB,
    full_result CLOB,
    result_truncated BOOLEAN DEFAULT FALSE NOT NULL,
    created_at VARCHAR(40) NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_tool_call_traces_run ON tool_call_traces(run_id);

CREATE TABLE IF NOT EXISTS workflow_plan_controls (
    run_id VARCHAR(36) PRIMARY KEY,
    execution_mode VARCHAR(30) DEFAULT 'STEP_TEACHING' NOT NULL,
    breakpoints_json CLOB DEFAULT '[]' NOT NULL,
    updated_at VARCHAR(40) NOT NULL
);
