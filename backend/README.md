# 后端源码说明

后端目前是根目录 `pom.xml` 构建的单个 Maven 工程。生产 Java 源码统一放在 `src/main/java/dev/workbench`，测试放在 `src/test/java`，配置和数据库初始化脚本放在 `src/main/resources`。目录与包按能力组织，并不等于独立部署模块。

| 当前目录 | 主要内容 |
| --- | --- |
| `framework/agent` | Agent 定义、执行契约和装配。 |
| `framework/model`、`framework/tool`、`framework/skill` | 模型、工具和技能的框架契约及部分控制。 |
| `framework/context`、`framework/workflow`、`framework/control`、`framework/event` | 上下文、计划与任务图、运行控制、事件。 |
| `infrastructure/agentscope`、`infrastructure/model` | AgentScope 适配器和模型适配实现。 |
| `agent`、`orchestration`、`task` | 仍在逐步拆分的 Agent 业务适配、固定流程编排和任务持久化。 |
| `memory`、`workspace`、`bug`、`evaluation`、`observability`、`report` | 记忆、工作区、Bug、评测、观测和报告相关实现。 |
| `app`、`domain` | 后端启动与配置、共享领域类型。 |

具体能力是否完成，以 [概要设计实现状态](../docs/开发记录/概要设计实现状态.md) 和 [开发记录](../docs/开发记录/开发记录.md) 为准。当前重构尚未完成：固定角色执行链、通用多节点调度和工具扩展的强制网关等仍需继续处理。

旧 `backend/modules` 和 `backend/app` 是迁移前的占位目录，与这里的 `src/main/java/dev/workbench/app` 不是同一个目录。旧说明原文见 [历史归档](../docs/历史记录/旧后端目录说明汇编.md)。
