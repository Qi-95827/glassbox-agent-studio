-- 同任务多轮调改迭代：同一 taskId 下每一轮运行的调改请求、上一轮交付摘要与目标节点。
-- 只追加新表，不改动任何历史表结构与既有数据；源运行的节点、模型 I/O、工具轨迹与交付记录保持不可变。
create table if not exists run_iterations (
  run_id varchar(36) primary key,
  task_id varchar(36) not null,
  parent_run_id varchar(36) not null,
  round_index int not null,
  target_node_key varchar(120),
  model_mode varchar(20) not null,
  model_connection_id varchar(64),
  feedback clob not null,
  previous_summary clob,
  created_at varchar(40) not null
);

create index if not exists idx_run_iterations_task on run_iterations (task_id, round_index);
