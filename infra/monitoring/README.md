# 工作台监控接入文件

- `prometheus-job.yml`：合并到现有 Prometheus 的 `scrape_configs` 下的独立任务；目标为 Docker Desktop 访问宿主机的本机 8088 端口。
- `grafana-dashboard.json`：复用 UID 为 `prometheus` 的数据源的独立看板；可通过 Grafana 导入，或放入已配置的文件看板目录。
- 指标定义、局限和验证方式见 [复用 Grafana 监控设计](../../docs/概要设计/首期_复用Grafana监控设计.md)。

抓取任务和看板不会创建 Grafana 账号或建立租户隔离。将配置应用到其他环境前，应检查端口、网络、数据源 UID 和访问权限。
