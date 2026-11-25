# 后端源码说明

后端是根目录 `pom.xml` 构建的单个 Maven 工程（Java 21 + Spring Boot 3.5.7）。生产 Java 源码统一放在 `src/main/java/dev/workbench`，测试放在 `src/test/java`，外置 Prompt、国际化资源与双库 Flyway 脚本放在 `src/main/resources`（H2 `db/migration` `V1~V14`，PostgreSQL 16 + pgvector `db/migration-pg` `V1~V4`）。目录与包按业务能力及分层守卫组织，由 `ArchitectureGuardTest`（ArchUnit）强制保证零循环依赖。

| 当前包 (`dev.workbench.*`) | 主要职责 |
| --- | --- |
| `domain` | 纯领域枚举与值对象（零持久化/Web 依赖）。 |
| `framework` | 框架内核契约、装配规划（`AgentAssemblyPlanner`）、`ToolGateway`（6.5 道安检与风险策略）、`OutboundUrlGuard`、压缩契约、`SecretCipher`、`SensitiveDataMasker` 与评测契约。 |
| `infrastructure` | AgentScope 2.0.4 适配（`AgentScopeExecutor`、`ControlledModel`）、BYOK/DeepSeek/Embedding 适配、安全 MCP 传输（STDIO/HTTP）与 PGVector JDBC 向量索引。 |
| `persistence` / `store` | JPA 实体（兼容 H2 `CLOB` 与 PG `TEXT`）、Spring Data Repository、投影视图与 `TransactionTemplate` 事务/事件编排门面。 |
| `planning` / `orchestration` / `task` | 想法整理、入口路由、候选 DAG 校验、受控并行调度与有界反思、非阻塞断点暂停/恢复、检查点恢复与费用预算账本。 |
| `control` | 四级上下文压缩（`MultiTierContextCompactor`）与归属隔离的 L1 产物卸载存储。 |
| `agent` / `tool` / `privateagent` | 工作区内置工具、Gate 5.5 HITL 工具审批与版本化私有 Agent/Skill/Tool 管理。 |
| `memory` / `workspace` / `bug` / `external` | V9 分层长期记忆与 V13 Hybrid RAG 知识库、Git worktree 与三段式交付、Docker `MOCK-PY-001` 容器验证与外部动作对账。 |
| `evaluation` / `observability` / `delivery` / `report` | Eval Lab v2 六维评测/Hub/A-B 对照、SSE/Prometheus/Grafana 看板、5 步诊断、执行图投影与 HTML 脱敏报告。 |
| `auth` / `ownership` / `audit` / `modelconnection` / `app` | 本地会话认证、CSRF、资源归属与 IDOR 隔离、审计台账、BYOK/Embedding 配置与异步线程池托管。 |

完整架构设计与最新验收基线见 [项目总览：产品需求与技术架构](../docs/项目总览_产品需求与技术架构.md) 与 [Top 10 安全止血与 P0 修复验收记录](../docs/交接/Top10安全止血与P0修复_验收记录.md)；历史占位目录说明见 [旧后端目录说明汇编](../docs/历史记录/旧后端目录说明汇编.md)。
