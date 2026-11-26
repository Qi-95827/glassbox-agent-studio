-- V4 时间旅行：从历史运行分叉出的分支运行留痕。
-- 只追加新表，不改动任何历史表结构与既有数据；源运行的证据行仍按原 runId 保留。
create table if not exists run_forks (
  run_id varchar(64) primary key,
  parent_run_id varchar(64) not null,
  source_node_key varchar(64),
  checkpoint_id varchar(64),
  prompt_addendum varchar(2000),
  model_mode varchar(32) not null,
  model_connection_id varchar(64),
  created_at varchar(64) not null
);

create index if not exists idx_run_forks_parent on run_forks (parent_run_id);
