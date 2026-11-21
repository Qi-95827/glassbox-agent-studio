# Agent Studio（可视化代码开发 Agent 工作台）

> 一个面向开发者与 AI Agent 学习者的**“玻璃盒（Glass-Box）”多 Agent 可视化协作工作台**。
> 不仅帮你完成需求整理、代码修改、测试验证与 Git 交付，更把多 Agent 如何被组装、如何规划、如何受控调用工具、如何交接证据与恢复的全过程透明呈现出来。

---

## 项目亮点

- **全流程可视化与可追溯**：通过 React Flow 执行流程图、逐步回放、节点详情抽屉与交付说明，查看每个节点的 Agent 装配快照、模型调用、工具调用、交接正文与检查证据。
- **可控的多 Agent 协作**：支持通用、需求、设计、代码、测试与模拟 Bug 多入口；规划阶段先生成结构化候选 DAG，由人工确认节点、模型、读写范围与必需产物后再落库执行。
- **严格的安全与权限边界**：工具调用统一经过 `ToolGateway` 鉴权、文件白名单校验、预算/重复调用限制与取消检查；代码修改在独立 Git worktree 中运行，提交/推送/合并需人工审批并绑定代码版本。
- **私有组件与混合模型计价**：支持本地多账号隔离，用户可创建并发布私有 Skill、只读工具配置与私有 Agent（固定版本）；费用账本支持同一运行内混合模型按调用级价格版本冻结与硬预算结算。
- **清晰的分层架构与架构守卫**：后端采用 Spring Boot 3.5 / Java 21 / Spring Data JPA / Flyway，由 ArchUnit 自动守卫零顶层包循环依赖与 AgentScope SDK 隔离；前端采用 React 19 + MUI（Google Material Design 3 风格）+ React Query + React Router。

---

## 快速启动

环境要求：**Java 21**、**Maven 3.9+**、**Node.js 20+**、**Git**（运行模拟 Bug 容器验证时需本机 Docker Desktop）。

### 1. 构建前端
```powershell
cd frontend/user-workbench
npm ci
npm run build
cd ../..
```

### 2. 启动后端服务
```powershell
mvn spring-boot:run
```

启动后访问：`http://127.0.0.1:8088`（首次打开时按页面引导初始化本地管理员账号；默认内置模拟模型，无需配置 API Key 即可体验完整多 Agent 协作与交接流程）。

### 3. 运行测试
```powershell
# 后端全量测试（含 ArchUnit 架构守卫）
mvn test

# 前端代码规范、单元测试与构建
cd frontend/user-workbench
npm run format:check
npm run lint
npm test
npm run build
```

---

## 接入真实模型（DeepSeek Flash）

将 API Key 粘贴到 `config/deepseek-api-key.txt`（仅一行密钥，无需引号，文件已加入 `.gitignore`），无需重启即可在工作台选择「DeepSeek Flash（真实调用）」。

> 提示：使用真实模型时，任务目标、工具描述、授权文件内容与相关测试输出会发送给模型提供方并产生费用；可结合「费用价格与硬预算」功能控制上限。

---

## 技术架构概览

```text
frontend/user-workbench/   React 19 · Vite · MUI (Google M3) · React Flow · React Query · React Router
backend/src/main/java/
  dev.workbench.domain          纯领域枚举、值对象与反序列化器（零持久化/Web 依赖）
  dev.workbench.framework       纯契约与无状态引擎内核（零业务/SDK 依赖）
  dev.workbench.infrastructure  AgentScope Java 2.0.4 与外部模型适配层
  dev.workbench.persistence     JPA Entity、Repository 与数据迁移
  dev.workbench.store           细粒度任务、运行状态、装配与证据存储服务
  dev.workbench.planning        想法整理、路由、候选计划生成与校验
  dev.workbench.orchestration   运行生命周期、DAG 调度、控制命令与交接协调
  dev.workbench.delivery        执行图投影、节点详情投影与交付聚合
  dev.workbench.workspace       授权文件读写搜索与 Git worktree 交付
  dev.workbench.privateagent    私有 Agent / Skill / Tool 配置与版本管理
  dev.workbench.bug             模拟 Bug 分诊、Docker 容器复现与回归闭环
  dev.workbench.evaluation      版本化评测套件、数据集与批次对照
  dev.workbench.observability   游标事件流、指标与监控告警
backend/src/main/resources/db/migration/
  V1__baseline.sql ~ V3__*.sql  Flyway 版本化数据库迁移（默认本机 H2 文件库）
```

---

## 文档导航

- **[docs/README.md](docs/README.md)**：完整文档目录与归档索引
- **[项目总览：产品需求与技术架构](docs/项目总览_产品需求与技术架构.md)**：当前版本能力范围、数据模型与验收基线
- **[玻璃盒多 Agent 全链路可视化概要设计](docs/概要设计/玻璃盒多Agent全链路可视化_概要设计.md)**：面向开源教学的全链路透明展示设计（含 UI 原型图与网关透视）
- **[代码开发规范](docs/开发规范/代码开发规范.md)**：工程分层、JPA 持久化与安全约定
- **[迭代计划与扩展方向](docs/迭代计划/1.1及以后_扩展方向.md)**：DAG 并行调度、通用测试沙箱、MySQL 适配等后续路线
