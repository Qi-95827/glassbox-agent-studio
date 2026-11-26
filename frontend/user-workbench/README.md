# Agent Studio · 用户工作台

React 19 + Vite + MUI + React Flow 的本地工作台，用于创建任务、确认计划、观察执行流程、回放事件、验收交付与管理私有 Agent。

## 技术栈

- React 19、Vite 6
- MUI（唯一 UI 组件库，Google Material Design 3 设计令牌）
- React Flow（`@xyflow/react`）与 Dagre 自动布局
- TanStack Query（运行轮询、缓存与写操作失效）
- React Router（HashRouter，刷新深链不依赖后端 SPA fallback）
- Vitest + Testing Library

## 本地启动

后端默认监听 `127.0.0.1:8088`，并从构建后的静态资源提供页面。

```powershell
cd frontend/user-workbench
npm ci
npm run build
cd ../..
mvn spring-boot:run
```

打开 <http://127.0.0.1:8088>。

开发时如需热更新，可运行 `npm run dev`（5173）。注意：后端的 `LocalOriginFilter`
要求请求 `Origin` 的主机与端口同源，因此 5173 的写请求（登录、创建任务等）会被拒绝；
需要联调写操作时请使用构建产物 + 8088，或单独调整后端开发代理策略。

## 命令

```powershell
npm run dev          # Vite 开发服务器
npm run build        # 生产构建
npm run format       # Prettier（100 字符行宽）
npm run lint         # ESLint
npm test             # Vitest
npm run test:watch   # 监听测试
```

## 目录结构

```
src/
  api/               # HTTP 客户端与按领域拆分的 API 模块
  app/               # QueryClient
  components/        # 状态胶囊、空状态、弹窗、Toast、ErrorBoundary、VirtualList 等通用组件
  constants/         # 状态、角色、工具、事件等业务字典
  features/
    auth/            # 登录与初始化
    tasks/           # 首页、任务编写器、三档授权条、候选计划与可视化编排画布（PlanComposerCanvas）
    runs/            # 运行详情、流程/回放/交付/列表、节点抽屉、玻璃盒（ReAct 泳道/六道安检流水线/上下文显微镜）、外部 Trace 与 OTel 导出
    checkpoints/     # 执行时间轴火焰图、Checkpoint 时间旅行与分叉、双跑对照与并排 Diff
    memory/          # Hybrid RAG 召回透视（BM25/向量 → RRF 漏斗）、三层记忆管理与文档切片边界
    architecture/    # C4 静态架构图谱与动态实时架构 X 光模式
    workflows/       # 流程图（React Flow + Memo）、预算、扩展证据、价格
    agents/          # 私有 Agent / Skill / 工具 / 账号
    workspaces/      # 工作区、管理员一键授权导入、Git 交付、文件、知识库与项目记忆
    evaluation/      # Eval Lab v2 六维评测、Benchmark Hub、自定义数据集与 A/B 消融对照
    monitoring/      # 类 Grafana 纯 SVG 细粒度运行监控看板
    bug/             # 模拟 Bug 入口与容器验证证据
    settings/ docs/  # BYOK 模型与 API Key、RAG Embedding 设置与使用文档
  hooks/             # 领域查询、SSE 实时流（含 280ms 防抖与按节点分桶）与标签页可见性 Hooks
  layout/            # 双栏应用壳、侧栏、顶栏、页面面包屑
  styles/            # ≤ 40 行全局基础样式
  theme/             # Google M3 设计令牌
  utils/             # 展示层格式化工具
```

## API 与状态约定

- 请求统一走 `src/api/client.js`：同源 Cookie、模块闭包 CSRF、按语言无关 `errorCode`（`CSRF_INVALID`）自动刷新重试一次、401 通知登录态失效。
- 10 个路由页采用 `React.lazy` 懒加载并由三级 `ErrorBoundary` 兜底；长事件回放列表使用自研 `VirtualList` 虚拟滚动。
- 运行详情优先通过 `/api/runs/{id}/stream` SSE 实时接收事件与流式增量帧（280ms 防抖刷新）；终态运行不建 SSE，SSE 连通或标签页隐藏时自动暂停 1800ms 兜底轮询。
- 写操作使用 `useMutation` 并在成功后精准失效相关查询；不使用 `globalThis`，不再手写分散的 `setInterval`。
- 所有后端路径、Method、CSRF 头与业务动作保持兼容；页面只展示服务端持久化记录，不补造执行事实。

## 设计系统

- 画布 `#F8F9FA`、侧栏 `#F0F4F9`、卡片白色 + 1px `#E0E3E7` 描边
- 主色 Google Blue `#1A73E8`；语义色使用浅底深字
- 卡片 16px、弹窗 24px、按钮/导航胶囊全圆角、输入框 12px
- 字体 Inter / Noto Sans SC / Roboto，代码与 ID 使用 JetBrains Mono
- 状态、按钮、表格、输入框等默认样式集中在 `src/theme/googleTheme.js`

## 已知限制

- 主要路由已按业务拆分，但部分大型面板仍超过 300 行；后续可继续按子视图拆分。
- 使用 HashRouter 以避免后端未配置 SPA 回退时刷新 404。
- 真实模型调用会产生费用；模拟与真实能力在界面中分别标示。
