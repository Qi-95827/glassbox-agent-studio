-- V4 时间旅行：分叉运行留痕（PostgreSQL 等价迁移）。
-- 表在 V1 基线建表中不存在，重复执行会显式失败而不是静默跳过。
create table run_forks (
  run_id varchar(64) primary key,
  parent_run_id varchar(64) not null,
  source_node_key varchar(64),
  checkpoint_id varchar(64),
  prompt_addendum varchar(2000),
  model_mode varchar(32) not null,
  model_connection_id varchar(64),
  created_at varchar(64) not null
);

create index idx_run_forks_parent on run_forks (parent_run_id);
