# backend\modules\observability

指标、日志和链路。

已实现基于数据库调用记录的单次运行 Agent 统计，区分已知与未知 Token 和耗时；Actuator 提供本地 Prometheus 格式的数据库快照及 JVM 指标。Grafana 实际接入、分布式 Trace、告警和完整平台统计尚未实现。

