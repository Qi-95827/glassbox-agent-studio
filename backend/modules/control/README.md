# backend\modules\control

预算、限额、取消与错误控制。

当前单进程实现位于 `src/main/java/dev/workbench/control`：`RunControl` 维护一次运行的计数、暂停检查、预算与调用记录；`StopExecution` 表示受控停止。暂停／取消信号和调度仍由编排层管理，尚不支持跨进程限额预留或分布式取消。具体边界见 `docs/概要设计/首期_运行控制模块拆分设计.md`。

