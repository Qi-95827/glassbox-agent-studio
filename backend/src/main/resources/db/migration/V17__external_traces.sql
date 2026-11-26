-- V6 外部 Agent 框架 Trace 接入：外部运行与外部 Span 留痕。
-- 只追加新表，不改动任何历史表结构；工作台运行（runs 表）与外部 Trace 严格分开存储。
create table if not exists external_trace_runs (
  id varchar(64) primary key,
  owner_scope_key varchar(128) not null,
  name varchar(200) not null,
  source varchar(80) not null,
  created_at varchar(64) not null
);

create index if not exists idx_external_trace_runs_owner
  on external_trace_runs (owner_scope_key, created_at);

create table if not exists external_trace_spans (
  id varchar(64) primary key,
  run_id varchar(64) not null,
  seq integer not null,
  kind varchar(32) not null,
  name varchar(200) not null,
  node_key varchar(64),
  status varchar(32),
  started_at varchar(64),
  ended_at varchar(64),
  attributes_json clob,
  created_at varchar(64) not null
);

create index if not exists idx_external_trace_spans_run
  on external_trace_spans (run_id, seq);
