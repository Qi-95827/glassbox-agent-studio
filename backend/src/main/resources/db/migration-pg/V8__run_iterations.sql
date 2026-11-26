-- 同任务多轮调改迭代（PostgreSQL 等价迁移）。
-- run_iterations 在既有基线建表中不存在，重复执行会显式失败而不是静默跳过。
create table run_iterations (
  run_id varchar(36) primary key,
  task_id varchar(36) not null,
  parent_run_id varchar(36) not null,
  round_index int not null,
  target_node_key varchar(120),
  model_mode varchar(20) not null,
  model_connection_id varchar(64),
  feedback text not null,
  previous_summary text,
  created_at varchar(40) not null
);

create index idx_run_iterations_task on run_iterations (task_id, round_index);
