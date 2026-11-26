-- V6 外部 Agent 框架 Trace 接入（PostgreSQL 等价迁移）。
-- 表在 V1 基线建表中不存在，重复执行会显式失败而不是静默跳过。
create table external_trace_runs (
  id varchar(64) primary key,
  owner_scope_key varchar(128) not null,
  name varchar(200) not null,
  source varchar(80) not null,
  created_at varchar(64) not null
);

create index idx_external_trace_runs_owner on external_trace_runs (owner_scope_key, created_at);

create table external_trace_spans (
  id varchar(64) primary key,
  run_id varchar(64) not null,
  seq integer not null,
  kind varchar(32) not null,
  name varchar(200) not null,
  node_key varchar(64),
  status varchar(32),
  started_at varchar(64),
  ended_at varchar(64),
  attributes_json text,
  created_at varchar(64) not null
);

create index idx_external_trace_spans_run on external_trace_spans (run_id, seq);
