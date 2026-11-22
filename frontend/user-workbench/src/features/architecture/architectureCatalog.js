/**
 * 架构图谱目录与节点元数据（中英双语）。
 * 所有类名与路径均对应本仓库真实实现，供学习者从架构图直达源码。
 */

export const ARCHITECTURE_GROUPS = [
  {
    id: 'overview',
    title: { 'zh-CN': '全局总览', 'en-US': 'System Overview' }
  },
  {
    id: 'runtime',
    title: { 'zh-CN': '核心执行与玻璃盒', 'en-US': 'Core Runtime & Glass-Box' }
  },
  {
    id: 'governance',
    title: { 'zh-CN': '安全、模型与可观测', 'en-US': 'Security, Models & Observability' }
  }
];

export const ARCHITECTURE_TOPICS = [
  {
    id: 'system-overview',
    group: 'overview',
    badge: '01',
    title: { 'zh-CN': '全栈系统总览', 'en-US': 'Full-Stack System Overview' },
    shortTitle: { 'zh-CN': '全栈总览', 'en-US': 'System Overview' },
    tagline: {
      'zh-CN':
        '单进程本地应用：React 工作台通过同源会话驱动五大引擎，将模型调用、文件授权与运行证据全量持久化。',
      'en-US':
        'Local single-process app: React workbench drives five core engines over same-origin sessions, persisting all calls, permissions, and evidence.'
    },
    defaultNode: 'orchestration',
    principles: [
      {
        title: { 'zh-CN': '单进程零运维', 'en-US': 'Zero-Ops Local Process' },
        body: {
          'zh-CN':
            '前端静态产物内嵌 Spring Boot，嵌入式 H2 文件库 + Flyway V6 自动迁移，开箱即跑。',
          'en-US':
            'Frontend bundle served by Spring Boot with embedded H2 file DB and Flyway V6 migrations out of the box.'
        }
      },
      {
        title: { 'zh-CN': '执行与编排解耦', 'en-US': 'Execution vs. Orchestration' },
        body: {
          'zh-CN': 'AgentScope 专注单节点 ReAct 推理，工作台自身掌控跨节点 DAG、权限、预算与恢复。',
          'en-US':
            'AgentScope handles single-node ReAct reasoning while the studio owns DAG scheduling, permissions, budgets, and recovery.'
        }
      },
      {
        title: { 'zh-CN': '全链路不可抵赖证据', 'en-US': 'Verifiable Evidence Trail' },
        body: {
          'zh-CN': '每次模型 I/O、安检门判定、交接校验和文件写入回执均独立落库，支持随时回放。',
          'en-US':
            'Every model I/O, gateway check, handoff verification, and file receipt is persisted for deterministic replay.'
        }
      }
    ],
    nodes: {
      ui: {
        label: { 'zh-CN': 'Agent Studio 工作台', 'en-US': 'Agent Studio UI' },
        role: { 'zh-CN': '前端表现层', 'en-US': 'Frontend Layer' },
        tone: 'blue',
        intent: {
          'zh-CN':
            '提供可视化 DAG 流程图、四维玻璃盒透视、BYOK 配置与 Grafana 风格监控看板，轮询拉取增量事件。',
          'en-US':
            'Renders the DAG flow graph, 4-panel glass-box inspector, BYOK settings, and monitoring dashboard via cursor-based event polling.'
        },
        codeRefs: ['WorkbenchShell.jsx', 'RunDetailPage.jsx', 'NodeGlassboxDialog.jsx']
      },
      api: {
        label: { 'zh-CN': 'Spring MVC & 安全边界', 'en-US': 'Spring MVC & Security' },
        role: { 'zh-CN': '接入与权限层', 'en-US': 'Access Boundary' },
        tone: 'green',
        intent: {
          'zh-CN':
            '统一承接 HTTP API，执行 Servlet Session 认证、CSRF 校验、Locale 解析及服务端所有权隔离。',
          'en-US':
            'Handles REST APIs with Servlet session auth, CSRF protection, Accept-Language locale resolution, and server-side ownership checks.'
        },
        codeRefs: [
          'SecurityConfiguration.java',
          'OwnershipService.java',
          'WebLocaleConfiguration.java'
        ]
      },
      planning: {
        label: { 'zh-CN': '任务整理与动态规划', 'en-US': 'Planning & Routing' },
        role: { 'zh-CN': '规划引擎', 'en-US': 'Planning Engine' },
        tone: 'blue',
        intent: {
          'zh-CN':
            '将自然语言整理为结构化任务，按规则路由或生成可编辑的候选 DAG 计划，确认前不产生任何副作用。',
          'en-US':
            'Structures user ideas, routes templates or drafts editable candidate DAGs with zero side effects prior to confirmation.'
        },
        codeRefs: ['TaskIdeaService.java', 'EntryRouteService.java', 'PlanCandidateValidator.java']
      },
      orchestration: {
        label: { 'zh-CN': 'DAG 运行编排器', 'en-US': 'DAG Orchestrator' },
        role: { 'zh-CN': '调度核心', 'en-US': 'Core Scheduler' },
        tone: 'blue',
        intent: {
          'zh-CN':
            '按拓扑依赖认领就绪节点，协调断点暂停、交接校验、系统检查与检查点恢复，维护运行状态机。',
          'en-US':
            'Claims ready nodes in topological order, coordinating breakpoints, handoff validation, system checks, and checkpoint recovery.'
        },
        codeRefs: ['WorkflowScheduler.java', 'WorkflowExecutionService.java', 'RunService.java']
      },
      agent: {
        label: { 'zh-CN': 'AgentScope 执行器', 'en-US': 'AgentScope Executor' },
        role: { 'zh-CN': '单节点推理', 'en-US': 'Node Reasoning' },
        tone: 'purple',
        intent: {
          'zh-CN':
            '依据冻结的装配快照构建独立 ReActAgent 实例，加载双语 Prompt 与受控工具，隔离上下文污染。',
          'en-US':
            'Builds an isolated ReActAgent per node from a frozen assembly snapshot with localized prompts and scoped tools.'
        },
        codeRefs: [
          'AgentAssemblyPlanner.java',
          'AgentScopeExecutor.java',
          'PromptResourceLoader.java'
        ]
      },
      gateway: {
        label: { 'zh-CN': 'Tool Gateway 安全网关', 'en-US': 'Tool Gateway' },
        role: { 'zh-CN': '六道安检门', 'en-US': '6 Security Gates' },
        tone: 'amber',
        intent: {
          'zh-CN':
            '拦截每一次工具调用，依次执行注册校验、取消态、配额、重复熔断、文件授权复验与写入回执记录。',
          'en-US':
            'Intercepts every tool call through 6 sequential gates: registry, cancellation, quotas, loop guard, file scope, and write receipts.'
        },
        codeRefs: ['ToolGateway.java', 'WorkspaceAuthorizationService.java']
      },
      model: {
        label: { 'zh-CN': '受控模型与 BYOK', 'en-US': 'Controlled Model & BYOK' },
        role: { 'zh-CN': '模型适配层', 'en-US': 'Model Adapter' },
        tone: 'purple',
        intent: {
          'zh-CN':
            '统一适配免 Key 模拟模型与 OpenAI 兼容端点，执行 AES-256-GCM 密钥解密、费用行锁预留与 I/O 落库。',
          'en-US':
            'Unifies simulated demo models and OpenAI-compatible BYOK endpoints with AES-256-GCM decryption, row-locked budgets, and I/O traces.'
        },
        codeRefs: ['ControlledModel.java', 'SecretCipher.java', 'UserModelConnectionFactory.java']
      },
      storage: {
        label: { 'zh-CN': 'H2 持久化 + Git / Docker', 'en-US': 'H2 DB + Git / Docker' },
        role: { 'zh-CN': '存储与沙箱', 'en-US': 'Storage & Sandboxes' },
        tone: 'green',
        intent: {
          'zh-CN':
            '82 张表记录全量运行事实；代码变更在独立 Git worktree 隔离执行；模拟 Bug 回归在固定 Docker 容器核验。',
          'en-US':
            '82 Flyway-managed tables store runtime facts; code edits run in isolated Git worktrees; bug tests verify inside pinned Docker containers.'
        },
        codeRefs: [
          'V1__baseline.sql ~ V6__task_locale.sql',
          'WorkspaceService.java',
          'BugService.java'
        ]
      }
    }
  },
  {
    id: 'layers-stack',
    group: 'overview',
    badge: '02',
    title: { 'zh-CN': '后端分层与前端模块架构', 'en-US': 'Backend Layers & Frontend Modules' },
    shortTitle: { 'zh-CN': '前后端分层', 'en-US': 'Tech Layers' },
    tagline: {
      'zh-CN':
        '后端由 ArchUnit 自动守卫零循环依赖与 SDK 隔离；前端按 Feature 垂直切片，React Query 统一管理服务端状态。',
      'en-US':
        'Backend enforces zero cycles and SDK isolation via ArchUnit; frontend slices vertically by feature with React Query managing server state.'
    },
    subViews: [
      {
        id: 'backend',
        label: { 'zh-CN': '后端分层架构 (Java 21)', 'en-US': 'Backend Layers (Java 21)' }
      },
      {
        id: 'frontend',
        label: { 'zh-CN': '前端模块架构 (React 19)', 'en-US': 'Frontend Modules (React 19)' }
      }
    ],
    defaultNode: 'be-app',
    principles: [
      {
        title: { 'zh-CN': 'ArchUnit 5 项硬守卫', 'en-US': '5 ArchUnit Guardrails' },
        body: {
          'zh-CN':
            '每次 mvn test 自动验证：顶层零环依赖、domain/framework 纯净、Controller 禁碰 JPA。',
          'en-US':
            'Every mvn test verifies zero top-level cycles, pure domain/framework, and zero JPA access from controllers.'
        }
      },
      {
        title: { 'zh-CN': 'SDK 严格隔离圈', 'en-US': 'Strict SDK Quarantine' },
        body: {
          'zh-CN':
            'io.agentscope.* 依赖全部收口在 infrastructure 与 persistence.agentscope，替换框架零侵入。',
          'en-US':
            'All io.agentscope.* imports are quarantined to infrastructure and persistence.agentscope for clean swappability.'
        }
      },
      {
        title: { 'zh-CN': '前端零重依赖设计', 'en-US': 'Lean Frontend Architecture' },
        body: {
          'zh-CN': 'MUI M3 主题 + 自研轻量 i18n + 纯 SVG 图表与架构图，打包轻快且风格高度统一。',
          'en-US':
            'MUI M3 tokens, zero-dep i18n, and pure React SVG charts/diagrams keep bundles lean and visually cohesive.'
        }
      }
    ],
    nodes: {
      'be-web': {
        label: { 'zh-CN': 'web / Controller 层', 'en-US': 'web / Controllers' },
        role: { 'zh-CN': 'HTTP 协议层', 'en-US': 'HTTP Layer' },
        tone: 'blue',
        intent: {
          'zh-CN':
            '仅做参数校验、Locale 提取、权限入口与 DTO 转换，严禁直接依赖 Spring Data Repository。',
          'en-US':
            'Handles request validation, locale extraction, auth boundaries, and DTO mapping; forbidden from touching JPA repositories.'
        },
        codeRefs: [
          'RunController.java',
          'ModelConnectionController.java',
          'MonitoringDashboardController.java'
        ]
      },
      'be-app': {
        label: {
          'zh-CN': '业务服务层 (planning / orchestration / task)',
          'en-US': 'Business Services'
        },
        role: { 'zh-CN': '核心编排层', 'en-US': 'Domain Services' },
        tone: 'blue',
        intent: {
          'zh-CN':
            '承载任务规划、DAG 调度、工作区交付、评测对比、BYOK 密钥与监控诊断等核心业务事务。',
          'en-US':
            'Owns task planning, DAG scheduling, workspace delivery, evaluation, BYOK connections, and monitoring diagnostics.'
        },
        codeRefs: [
          'TaskCreationService.java',
          'WorkflowExecutionService.java',
          'RunDiagnosticsService.java'
        ]
      },
      'be-framework': {
        label: { 'zh-CN': 'domain & framework 契约层', 'en-US': 'domain & framework' },
        role: { 'zh-CN': '纯净抽象层', 'en-US': 'Pure Contracts' },
        tone: 'green',
        intent: {
          'zh-CN':
            '定义 Agent、工具、模型、上下文与工作流接口，零 Spring / JPA / AgentScope 外部框架污染。',
          'en-US':
            'Defines core Agent, Tool, Model, Context, and Workflow contracts free from Spring, JPA, or AgentScope dependencies.'
        },
        codeRefs: ['AgentExecutor.java', 'ToolGateway.java', 'LocalizedMessages.java']
      },
      'be-infra': {
        label: {
          'zh-CN': 'infrastructure (AgentScope 隔离圈)',
          'en-US': 'infrastructure (SDK Quarantine)'
        },
        role: { 'zh-CN': '外部适配层', 'en-US': 'External Adapters' },
        tone: 'purple',
        intent: {
          'zh-CN':
            '唯一允许直接调用 AgentScope Java SDK、OpenAI HTTP 客户端与外部模型探测的隔离模块。',
          'en-US':
            'The sole module permitted to call AgentScope Java SDK, OpenAI-compatible HTTP clients, and model probes.'
        },
        codeRefs: ['AgentScopeExecutor.java', 'OpenAICompatibleModelProbe.java', 'DemoModel.java']
      },
      'be-persistence': {
        label: { 'zh-CN': 'store & persistence (JPA + Flyway)', 'en-US': 'store & persistence' },
        role: { 'zh-CN': '数据访问层', 'en-US': 'Data Layer' },
        tone: 'amber',
        intent: {
          'zh-CN':
            'Spring Data JPA Repository + 细粒度 Store 封装，由 Flyway V1~V6 严格管理 82 张表结构演进。',
          'en-US':
            'Spring Data JPA repositories wrapped in fine-grained stores, with 82 tables strictly versioned via Flyway V1–V6.'
        },
        codeRefs: ['ArchitectureGuardTest.java', 'V6__task_locale.sql']
      },
      'fe-shell': {
        label: { 'zh-CN': 'WorkbenchShell & 路由', 'en-US': 'WorkbenchShell & Router' },
        role: { 'zh-CN': '应用骨架', 'en-US': 'App Shell' },
        tone: 'blue',
        intent: {
          'zh-CN':
            'HashRouter + AuthGate 会话守卫 + 左侧导航与 TopBar（语言切换 / 面包屑 / 全局状态）。',
          'en-US':
            'HashRouter with AuthGate session guard, persistent sidebar navigation, and TopBar with language toggle and breadcrumbs.'
        },
        codeRefs: ['App.jsx', 'WorkbenchShell.jsx', 'Sidebar.jsx', 'TopBar.jsx']
      },
      'fe-features': {
        label: { 'zh-CN': 'Features 垂直业务切片', 'en-US': 'Feature Slices' },
        role: { 'zh-CN': '页面与交互', 'en-US': 'Feature Modules' },
        tone: 'purple',
        intent: {
          'zh-CN':
            '按任务、运行玻璃盒、私有 Agent、工作区、评测监控、设置与架构图谱分治，单组件职责清晰。',
          'en-US':
            'Vertically sliced into tasks, runs/glassbox, agents, workspaces, evaluation/monitoring, settings, and architecture.'
        },
        codeRefs: ['features/runs/', 'features/evaluation/', 'features/architecture/']
      },
      'fe-hooks': {
        label: { 'zh-CN': 'React Query Hooks & i18n', 'en-US': 'React Query & i18n' },
        role: { 'zh-CN': '状态与双语', 'en-US': 'State & Locale' },
        tone: 'green',
        intent: {
          'zh-CN': '集中管理缓存、1.8s 增量轮询与失效刷新；I18nProvider 驱动全站实时中英文切换。',
          'en-US':
            'Centralizes server caching, 1.8s cursor polling, and invalidations; I18nProvider powers instant zh-CN/en-US switching.'
        },
        codeRefs: ['useRunExecution.js', 'useModelConnections.js', 'I18nProvider.jsx']
      },
      'fe-api': {
        label: { 'zh-CN': 'API Client (CSRF + Accept-Language)', 'en-US': 'API Client' },
        role: { 'zh-CN': '网络边界', 'en-US': 'Network Layer' },
        tone: 'amber',
        intent: {
          'zh-CN': '自动携带同源 Cookie、CSRF Token 与当前界面语言头，统一规范化后端错误提示。',
          'en-US':
            'Automatically attaches same-origin cookies, CSRF tokens, and Accept-Language headers with normalized error handling.'
        },
        codeRefs: ['api/client.js', 'api/runs.js', 'api/modelConnections.js']
      }
    }
  },
  {
    id: 'run-lifecycle',
    group: 'runtime',
    badge: '03',
    title: {
      'zh-CN': '一次运行如何形成（DAG 编排）',
      'en-US': 'Lifecycle of a Run (DAG Orchestration)'
    },
    shortTitle: { 'zh-CN': '运行与 DAG', 'en-US': 'Run & DAG' },
    tagline: {
      'zh-CN':
        '从一句话想法到可核对交付物：先规划确认、再拓扑调度、最后在安全检查点上支持随时暂停与恢复。',
      'en-US':
        'From a one-line idea to verified deliverables: plan and confirm first, then schedule topologically with checkpointed recovery.'
    },
    defaultNode: 'schedule',
    principles: [
      {
        title: { 'zh-CN': '确认前零副作用', 'en-US': 'Zero Side Effects Before Confirm' },
        body: {
          'zh-CN': '生成候选图只读不写，用户确认计划、模型与文件范围后，才在同一事务中冻结落库。',
          'en-US':
            'Drafting candidates is strictly read-only; execution starts only after user confirms nodes, models, and file scope.'
        }
      },
      {
        title: { 'zh-CN': '拓扑依赖与写集互斥', 'en-US': 'Topological & Disjoint Writes' },
        body: {
          'zh-CN': '规划校验器强制无环、节点写集属于读集、并行开发分支写集互斥，杜绝覆盖冲突。',
          'en-US':
            'Validator enforces acyclicity, write-subset-of-read, and disjoint write sets across parallel dev branches.'
        }
      },
      {
        title: { 'zh-CN': '安全检查点可恢复', 'en-US': 'Safe Checkpoint Recovery' },
        body: {
          'zh-CN': '每个节点结算后保存检查点，中断或补充信息后可派生子运行，复用已核验成果。',
          'en-US':
            'Checkpoints saved at node settlement allow spawning recovery runs that reuse verified upstream results.'
        }
      }
    ],
    nodes: {
      idea: {
        label: { 'zh-CN': '1. 想法整理与路由', 'en-US': '1. Idea & Entry Route' },
        role: { 'zh-CN': '入口阶段', 'en-US': 'Intake' },
        tone: 'blue',
        intent: {
          'zh-CN': '补全目标、约束、交付物与验收条件，按入口规则决定走固定模板还是动态规划。',
          'en-US':
            'Expands goals, constraints, deliverables, and acceptance criteria, then routes to fixed templates or dynamic planning.'
        },
        codeRefs: ['TaskIdeaService.java', 'EntryRouteService.java']
      },
      plan: {
        label: { 'zh-CN': '2. 候选计划与校验', 'en-US': '2. Candidate & Validation' },
        role: { 'zh-CN': '静态检查', 'en-US': 'Static Checks' },
        tone: 'amber',
        intent: {
          'zh-CN': '校验节点键、DAG 无环、写集互斥、Agent 版本与预算，确保计划可执行且边界明确。',
          'en-US':
            'Validates node keys, DAG acyclicity, disjoint write scopes, pinned Agent versions, and budgets before approval.'
        },
        codeRefs: ['PlanCandidateService.java', 'PlanCandidateValidator.java']
      },
      confirm: {
        label: { 'zh-CN': '3. 人工确认与事务落库', 'en-US': '3. Confirm & Freeze' },
        role: { 'zh-CN': '边界冻结', 'en-US': 'Scope Freeze' },
        tone: 'green',
        intent: {
          'zh-CN':
            '核对候选摘要与文件基线，在单一事务中写入任务、运行、计划、locale 与授权，提交后异步派发。',
          'en-US':
            'Verifies candidate digest and file baseline, persisting task, run, plan, locale, and permissions atomically.'
        },
        codeRefs: ['TaskCreationService.java', 'RunCreationService.java']
      },
      schedule: {
        label: { 'zh-CN': '4. DAG 拓扑调度执行', 'en-US': '4. DAG Topological Run' },
        role: { 'zh-CN': '核心循环', 'en-US': 'Scheduler Loop' },
        tone: 'blue',
        intent: {
          'zh-CN':
            '认领全部上游均已完成的就绪节点，支持教学断点暂停、单节点装配执行与系统检查节点。',
          'en-US':
            'Claims nodes whose dependencies have settled, supporting teaching breakpoints, isolated agent runs, and system checks.'
        },
        codeRefs: ['WorkflowScheduler.java', 'WorkflowExecutionService.java']
      },
      handoff: {
        label: { 'zh-CN': '5. 交接校验与结算', 'en-US': '5. Handoff & Settlement' },
        role: { 'zh-CN': '证据关卡', 'en-US': 'Evidence Gate' },
        tone: 'amber',
        intent: {
          'zh-CN':
            '核验必需产物与交接契约，缺失文件时以 OUTPUT_MISSING 阻断，防止下游基于幻觉继续。',
          'en-US':
            'Verifies required outputs and handoff contracts; blocks with OUTPUT_MISSING so downstream never runs on unverified claims.'
        },
        codeRefs: ['HandoffValidator.java', 'StructuredHandoffExtractor.java']
      },
      checkpoint: {
        label: { 'zh-CN': '6. 检查点恢复与交付', 'en-US': '6. Checkpoint & Delivery' },
        role: { 'zh-CN': '终态与复用', 'en-US': 'Terminal & Reuse' },
        tone: 'green',
        intent: {
          'zh-CN': '生成可下载交付投影与 HTML 报告；若运行受阻，可从最近安全检查点派生恢复运行。',
          'en-US':
            'Builds delivery projections and HTML reports; blocked runs can spawn a recovery run from the last safe checkpoint.'
        },
        codeRefs: ['RunDeliveryService.java', 'RunRecoveryService.java']
      }
    }
  },
  {
    id: 'tool-gateway',
    group: 'runtime',
    badge: '04',
    title: { 'zh-CN': 'Tool Gateway 与六道安检门', 'en-US': 'Tool Gateway & 6 Security Gates' },
    shortTitle: { 'zh-CN': '六道安检门', 'en-US': 'Tool Gateway' },
    tagline: {
      'zh-CN':
        'Agent 不能直接碰文件系统或外部命令——每次工具调用必须串联通过 6 道服务端安检门，任一不通过即刻 DENY。',
      'en-US':
        'Agents never touch files directly—every tool call must clear 6 sequential server-side security gates; any failure triggers immediate DENY.'
    },
    defaultNode: 'gate5',
    principles: [
      {
        title: { 'zh-CN': '默认拒绝（Fail-Closed）', 'en-US': 'Fail-Closed by Default' },
        body: {
          'zh-CN': '6 道安检门全部显式记录 PASS / DENY / SKIPPED，任何异常或超界立刻阻断并留痕。',
          'en-US':
            'All 6 gates explicitly record PASS / DENY / SKIPPED; any scope violation or error halts execution with full audit.'
        }
      },
      {
        title: { 'zh-CN': '运行时二次复验授权', 'en-US': 'Runtime Scope Re-Check' },
        body: {
          'zh-CN':
            '即使工具已装配给 Agent，执行瞬间仍会按“任务授权 ∩ 节点声明 ∩ 规范化路径”重新裁决。',
          'en-US':
            'Even for granted tools, execution re-verifies Task Scope ∩ Node Declaration ∩ Normalized Path at call time.'
        }
      },
      {
        title: { 'zh-CN': '可纠正错误回传模型', 'en-US': 'Correctable Error Feedback' },
        body: {
          'zh-CN':
            '被拦截时会把结构化拒绝原因返回给模型，让学习者清晰观察 Agent 如何自我修正或安全停止。',
          'en-US':
            'Structured denial reasons return to the model so learners can watch the agent self-correct or stop safely.'
        }
      }
    ],
    nodes: {
      caller: {
        label: { 'zh-CN': 'Agent 发起工具调用', 'en-US': 'Agent Tool Request' },
        role: { 'zh-CN': '调用入口', 'en-US': 'Entry Point' },
        tone: 'purple',
        intent: {
          'zh-CN':
            'ReActAgent 输出 tool_calls JSON 参数，由 ToolGateway 统一接管并分配调用追踪 ID。',
          'en-US':
            'ReActAgent emits tool_calls JSON arguments; ToolGateway intercepts the request and assigns a trace ID.'
        },
        codeRefs: ['AgentScopeExecutor.java', 'ToolGateway.java']
      },
      gate1: {
        label: { 'zh-CN': '门 1 · 工具注册与版本校验', 'en-US': 'Gate 1 · Registry & Contract' },
        role: { 'zh-CN': '安检门 #1', 'en-US': 'Security Gate #1' },
        tone: 'amber',
        intent: {
          'zh-CN':
            '确认目标工具已在本节点装配清单中注册，核对工具契约版本，杜绝模型幻觉调用未授权工具。',
          'en-US':
            'Verifies the requested tool exists in this node’s assembled allowlist and matches the pinned contract version.'
        },
        codeRefs: ['ToolGateway.java']
      },
      gate2: {
        label: { 'zh-CN': '门 2 · 运行取消与暂停态检查', 'en-US': 'Gate 2 · Cancel / Pause State' },
        role: { 'zh-CN': '安检门 #2', 'en-US': 'Security Gate #2' },
        tone: 'amber',
        intent: {
          'zh-CN': '在执行前实时检查用户是否已点击取消或暂停运行，确保人工接管指令毫秒级生效。',
          'en-US':
            'Checks live run status for user cancellation or pause requests so human stop signals take effect immediately.'
        },
        codeRefs: ['ToolGateway.java', 'RunControlService.java']
      },
      gate3: {
        label: { 'zh-CN': '门 3 · 角色与工具次数配额', 'en-US': 'Gate 3 · Role & Tool Quotas' },
        role: { 'zh-CN': '安检门 #3', 'en-US': 'Security Gate #3' },
        tone: 'amber',
        intent: {
          'zh-CN': '校验运行级、角色级与单工具类型调用上限，防止失控 Agent 无限刷工具耗尽预算。',
          'en-US':
            'Enforces run-level, role-level, and per-tool-type call limits to prevent runaway tool loops.'
        },
        codeRefs: ['ToolGateway.java']
      },
      gate4: {
        label: { 'zh-CN': '门 4 · 重复调用熔断保护', 'en-US': 'Gate 4 · Duplicate Loop Guard' },
        role: { 'zh-CN': '安检门 #4', 'en-US': 'Security Gate #4' },
        tone: 'amber',
        intent: {
          'zh-CN': '对相同工具与参数的连续无效重试触发熔断，打破模型死循环并给出可观测告警。',
          'en-US':
            'Trips a circuit breaker on repeated identical tool/argument calls to break model deadlocks.'
        },
        codeRefs: ['ToolGateway.java']
      },
      gate5: {
        label: {
          'zh-CN': '门 5 · 工作区文件授权交集复验',
          'en-US': 'Gate 5 · File Scope Re-Check'
        },
        role: { 'zh-CN': '安检门 #5（最关键）', 'en-US': 'Security Gate #5 (Critical)' },
        tone: 'amber',
        intent: {
          'zh-CN':
            '规范化路径防御目录穿越（..），逐条核对读写权限交集，越权文件读取或写入 100% 拦截。',
          'en-US':
            'Normalizes paths against traversal (..) and verifies read/write authorization intersection—blocking all out-of-scope access.'
        },
        codeRefs: ['WorkspaceAuthorizationService.java', 'ToolGateway.java']
      },
      gate6: {
        label: { 'zh-CN': '门 6 · 受控执行与写入回执', 'en-US': 'Gate 6 · Execute & Receipt' },
        role: { 'zh-CN': '放行与落库', 'en-US': 'Execute & Audit' },
        tone: 'green',
        intent: {
          'zh-CN':
            '仅当 5 道门全绿才真正执行，生成统一工具结果、SHA-256 文件写入回执并全量记录进玻璃盒。',
          'en-US':
            'Executes only when prior gates pass, recording normalized output, SHA-256 write receipts, and full glass-box trace.'
        },
        codeRefs: ['ToolGateway.java', 'WorkspaceService.java']
      }
    }
  },
  {
    id: 'agent-assembly',
    group: 'runtime',
    badge: '05',
    title: {
      'zh-CN': 'Agent 装配配方与独立沙箱实例',
      'en-US': 'Agent Assembly & Isolated Instance'
    },
    shortTitle: { 'zh-CN': '装配配方', 'en-US': 'Agent Assembly' },
    tagline: {
      'zh-CN':
        '节点启动前，服务端将角色 Prompt、私有 Skill、权限漏斗过滤后的工具与显式上下文冻结为快照，再实例化独立 Agent。',
      'en-US':
        'Before a node runs, the server freezes Role Prompt, Skills, scoped tools, and explicit context into a snapshot to spawn an isolated Agent.'
    },
    defaultNode: 'snapshot',
    principles: [
      {
        title: { 'zh-CN': '工具权限漏斗', 'en-US': 'Tool Permission Funnel' },
        body: {
          'zh-CN':
            '只装配“角色需要 ∩ 工作区授权”的工具，未被授予的工具连 Schema 都不会暴露给模型。',
          'en-US':
            'Only tools in Role Needs ∩ Workspace Scope are registered; denied tools never even appear in the model schema.'
        }
      },
      {
        title: { 'zh-CN': '显式上下文溯源', 'en-US': 'Explicit Context Provenance' },
        body: {
          'zh-CN': '每条喂给 Agent 的任务、项目记忆与上游交接都带来源 ID，杜绝“隐形记忆污染”。',
          'en-US':
            'Every task input, project memory, and upstream handoff carries a provenance ID—zero hidden prompt injection.'
        }
      },
      {
        title: { 'zh-CN': '一节点一实例', 'en-US': 'One Instance per Node' },
        body: {
          'zh-CN': '不同节点绝不共享可变会话内存，审查节点与开发节点彻底隔离，确保独立审查客观性。',
          'en-US':
            'Nodes never share mutable session memory; reviewers run in fresh isolated instances independent of developers.'
        }
      }
    ],
    nodes: {
      prompt: {
        label: { 'zh-CN': '角色双语 Prompt', 'en-US': 'Bilingual Role Prompt' },
        role: { 'zh-CN': '输入配方 #1', 'en-US': 'Ingredient #1' },
        tone: 'blue',
        intent: {
          'zh-CN':
            '按任务的 locale 自动从 prompts/ 或 prompts/en/ 加载外置角色提示词，缺失时自动安全回退。',
          'en-US':
            'Loads externalized role prompts from prompts/ or prompts/en/ based on task locale with safe fallback.'
        },
        codeRefs: ['PromptResourceLoader.java', 'resources/prompts/en/']
      },
      skills: {
        label: { 'zh-CN': '私有 Markdown Skill', 'en-US': 'Private Markdown Skills' },
        role: { 'zh-CN': '输入配方 #2', 'en-US': 'Ingredient #2' },
        tone: 'purple',
        intent: {
          'zh-CN': '按固定版本号注入用户发布的方法论与检查清单，版本锁定确保历史运行可精确复现。',
          'en-US':
            'Injects version-pinned user Markdown checklists and methods so historical runs remain reproducible.'
        },
        codeRefs: ['AgentAssemblyPlanner.java', 'AgentCatalogService.java']
      },
      tools: {
        label: { 'zh-CN': '工具权限漏斗交集', 'en-US': 'Scoped Tool Funnel' },
        role: { 'zh-CN': '输入配方 #3', 'en-US': 'Ingredient #3' },
        tone: 'amber',
        intent: {
          'zh-CN':
            '从 4 类核心工具 + 私有只读工具中筛选出本节点允许集合，并记录每个被过滤工具的拒绝原因。',
          'en-US':
            'Filters core and private read-only tools down to the allowed intersection, logging why each excluded tool was filtered.'
        },
        codeRefs: ['AgentAssemblyPlanner.java', 'AgentToolProvider.java']
      },
      context: {
        label: { 'zh-CN': '授权上下文来源', 'en-US': 'Authorized Context Sources' },
        role: { 'zh-CN': '输入配方 #4', 'en-US': 'Ingredient #4' },
        tone: 'green',
        intent: {
          'zh-CN':
            '仅组装当前任务说明、用户确认的项目记忆、受信系统检查与已通过校验的直接上游交接。',
          'en-US':
            'Assembles only the task brief, user-confirmed project memory, trusted system checks, and accepted upstream handoffs.'
        },
        codeRefs: ['AgentAssemblyPlanner.java']
      },
      snapshot: {
        label: { 'zh-CN': '冻结装配快照 (Assembly Snapshot)', 'en-US': 'Frozen Assembly Snapshot' },
        role: { 'zh-CN': '玻璃盒留证', 'en-US': 'Glass-Box Proof' },
        tone: 'blue',
        intent: {
          'zh-CN':
            '在真正调用模型前把完整配方序列化落库，玻璃盒“装配配方”面板展示的就是这份不可变快照。',
          'en-US':
            'Persists the complete recipe before model invocation; this immutable snapshot powers the Assembly Recipe panel.'
        },
        codeRefs: ['AgentAssemblyPlanner.java', 'AssemblyBlueprintPanel.jsx']
      },
      instance: {
        label: { 'zh-CN': '独立 AgentScope ReActAgent', 'en-US': 'Isolated ReActAgent Instance' },
        role: { 'zh-CN': '执行沙箱', 'en-US': 'Execution Sandbox' },
        tone: 'purple',
        intent: {
          'zh-CN':
            '绑定本次尝试的 ControlledModel 与 ToolGateway 代理，执行完毕即销毁，不遗留跨节点状态。',
          'en-US':
            'Binds ControlledModel and ToolGateway proxies for this attempt only, then disposes cleanly with zero state leakage.'
        },
        codeRefs: ['AgentScopeExecutor.java']
      }
    }
  },
  {
    id: 'handoff-envelope',
    group: 'runtime',
    badge: '06',
    title: {
      'zh-CN': 'Handoff Envelope 交接信封与校验管道',
      'en-US': 'Handoff Envelope & Verification'
    },
    shortTitle: { 'zh-CN': '交接信封', 'en-US': 'Handoff Envelope' },
    tagline: {
      'zh-CN':
        '多 Agent 协作不是“把上一段聊天记录直接转发”，而是经过结构化提取与 5 项证据门禁的受控信封投递。',
      'en-US':
        'Multi-agent collaboration is not raw chat forwarding—outputs pass through structured extraction and 5 evidence gates before delivery.'
    },
    defaultNode: 'validator',
    principles: [
      {
        title: { 'zh-CN': '拒绝无证据口头宣称', 'en-US': 'No Unverified Claims' },
        body: {
          'zh-CN':
            '上游说“我改了文件/跑了测试”不算数，信封校验器只认服务端持久化写入回执与检查记录。',
          'en-US':
            'Claims like "tests passed" are ignored unless backed by persisted write receipts and system check records.'
        }
      },
      {
        title: { 'zh-CN': '结构化信封减噪', 'en-US': 'Structured Noise Reduction' },
        body: {
          'zh-CN': '自动提取摘要、结论、风险与产物列表，避免下游被上游冗长草稿撑爆上下文窗口。',
          'en-US':
            'Extracts summary, decisions, risks, and artifacts so downstream contexts stay focused and compact.'
        }
      },
      {
        title: { 'zh-CN': '双向可追溯', 'en-US': 'Bidirectional Traceability' },
        body: {
          'zh-CN':
            '同时记录发送方/接收方的节点 ID、Agent 实例 ID 与尝试 ID，点击连线即可透视信封。',
          'en-US':
            'Links sender and receiver node IDs, agent IDs, and attempt IDs—click any graph edge to inspect.'
        }
      }
    ],
    nodes: {
      upstream: {
        label: { 'zh-CN': '上游节点结算产出', 'en-US': 'Upstream Node Output' },
        role: { 'zh-CN': '发送方', 'en-US': 'Sender' },
        tone: 'blue',
        intent: {
          'zh-CN': '上游节点完成推理并结算状态，同时关联本次尝试产生的工具调用与文件写入回执。',
          'en-US':
            'Upstream node settles its result along with tool call records and file write receipts from that attempt.'
        },
        codeRefs: ['WorkflowExecutionService.java']
      },
      extractor: {
        label: { 'zh-CN': '双语结构化信封提取', 'en-US': 'Structured Envelope Extractor' },
        role: { 'zh-CN': '清洗与封装', 'en-US': 'Extraction' },
        tone: 'purple',
        intent: {
          'zh-CN':
            '识别中英文关键词、过滤 Markdown 噪声，将原始正文封装为摘要、决策、风险与产物清单。',
          'en-US':
            'Parses zh/en keywords and strips Markdown noise to package summary, decisions, risks, and artifacts.'
        },
        codeRefs: ['StructuredHandoffExtractor.java']
      },
      validator: {
        label: { 'zh-CN': '5 项交接校验门禁', 'en-US': '5 Handoff Validation Gates' },
        role: { 'zh-CN': '核心安检', 'en-US': 'Verification' },
        tone: 'amber',
        intent: {
          'zh-CN':
            '逐项核验：①直接上游依赖 ②上游已完成 ③必需产物齐备 ④写入摘要一致 ⑤证据归属当前运行。',
          'en-US':
            'Checks 5 gates: direct dependency, completed status, required outputs present, file digest match, and run ownership.'
        },
        codeRefs: ['HandoffValidator.java', 'HandoffEnvelopePanel.jsx']
      },
      block: {
        label: { 'zh-CN': 'DENY · 阻断交接', 'en-US': 'DENY · Block Handoff' },
        role: { 'zh-CN': '失败分支', 'en-US': 'Rejection Path' },
        tone: 'amber',
        intent: {
          'zh-CN':
            '任意校验失败立即拒绝投递，记录具体原因码（如 OUTPUT_MISSING）并通知用户补充或复核。',
          'en-US':
            'Any failed check blocks delivery, records the reason code (e.g., OUTPUT_MISSING), and alerts the user.'
        },
        codeRefs: ['HandoffValidator.java']
      },
      downstream: {
        label: { 'zh-CN': 'PASS · 注入下游上下文', 'en-US': 'PASS · Inject Downstream' },
        role: { 'zh-CN': '接收方', 'en-US': 'Receiver' },
        tone: 'green',
        intent: {
          'zh-CN': '信封签收落库后，作为受信上下文来源正式注入下游节点的装配配方中。',
          'en-US':
            'Accepted envelopes are persisted and injected as trusted context sources into the downstream assembly recipe.'
        },
        codeRefs: ['AgentAssemblyPlanner.java', 'HandoffEnvelopePanel.jsx']
      }
    }
  },
  {
    id: 'model-byok',
    group: 'governance',
    badge: '07',
    title: {
      'zh-CN': 'BYOK 加密、受控模型与预算账本',
      'en-US': 'BYOK Encryption, Controlled Model & Budget'
    },
    shortTitle: { 'zh-CN': 'BYOK 与预算', 'en-US': 'BYOK & Budget' },
    tagline: {
      'zh-CN':
        '用户自带密钥本地 AES-256-GCM 加密存储；每次模型请求经 ControlledModel 拦截，实行“行锁预留 → 调用 → 独立结算”。',
      'en-US':
        'User API keys are encrypted locally with AES-256-GCM; ControlledModel wraps every call with row-locked reserve, invoke, and settle.'
    },
    defaultNode: 'controlled',
    principles: [
      {
        title: { 'zh-CN': '密钥零明文回显', 'en-US': 'Zero Plaintext Echo' },
        body: {
          'zh-CN':
            '主密钥位于 config/master.key（已 gitignore），API 列表永远只返回脱敏掩码（如 sk-****7a9f）。',
          'en-US':
            'Master key lives in gitignored config/master.key; API responses only ever return masked keys (e.g., sk-****7a9f).'
        }
      },
      {
        title: { 'zh-CN': '并发安全的硬预算账本', 'en-US': 'Concurrency-Safe Hard Budget' },
        body: {
          'zh-CN': '调用前按数据库行锁预留额度，完成后独立事务按实际 Token 结算多退少补，不超支。',
          'en-US':
            'Reserves budget via DB row lock before calling, then settles exact token usage in an independent transaction.'
        }
      },
      {
        title: { 'zh-CN': '全量 I/O 透明留痕', 'en-US': 'Full Model I/O Trace' },
        body: {
          'zh-CN': '发给模型的完整 Messages、Tool Schema 与模型原始回复实时记录，玻璃盒一键透视。',
          'en-US':
            'Full request messages, tool schemas, and raw model responses are persisted for instant glass-box inspection.'
        }
      }
    ],
    nodes: {
      vault: {
        label: { 'zh-CN': 'BYOK 加密密钥库 (AES-256-GCM)', 'en-US': 'BYOK Encrypted Vault' },
        role: { 'zh-CN': '凭据保护', 'en-US': 'Secret Storage' },
        tone: 'green',
        intent: {
          'zh-CN':
            '每条连接使用独立 12 字节随机 IV 加密存入 H2，支持 DeepSeek / OpenAI / 硅基流动 / 本地 Ollama。',
          'en-US':
            'Encrypts each key with a unique 12-byte random IV in H2; supports DeepSeek, OpenAI, SiliconFlow, and local Ollama.'
        },
        codeRefs: ['SecretCipher.java', 'ModelConnectionService.java', 'ApiKeyMasker.java']
      },
      factory: {
        label: { 'zh-CN': '模型工厂与连通性探针', 'en-US': 'Model Factory & Probe' },
        role: { 'zh-CN': '动态解析', 'en-US': 'Dynamic Resolution' },
        tone: 'blue',
        intent: {
          'zh-CN':
            '运行时按归属校验并即时解密构建客户端；配置页支持一键发送最小请求探测延迟与连通性。',
          'en-US':
            'Verifies ownership and decrypts on demand to build clients; settings page runs lightweight connectivity probes.'
        },
        codeRefs: ['UserModelConnectionFactory.java', 'OpenAICompatibleModelProbe.java']
      },
      controlled: {
        label: { 'zh-CN': 'ControlledModel 拦截器', 'en-US': 'ControlledModel Wrapper' },
        role: { 'zh-CN': '控制中枢', 'en-US': 'Control Hub' },
        tone: 'purple',
        intent: {
          'zh-CN':
            '统一包裹真实模型与免 Key 模拟模型，串联停止信号检查、Token/费用预留与请求载荷采集。',
          'en-US':
            'Wraps both live and simulated models, enforcing stop signals, token/cost reservation, and payload capture.'
        },
        codeRefs: ['ControlledModel.java', 'DemoModel.java']
      },
      ledger: {
        label: { 'zh-CN': '行锁预留 & 独立事务结算', 'en-US': 'Row-Lock Reserve & Settle' },
        role: { 'zh-CN': '费用账本', 'en-US': 'Cost Ledger' },
        tone: 'amber',
        intent: {
          'zh-CN': '在冻结价格版本下执行预留与结算，避免长耗时模型调用长时间占用业务事务连接。',
          'en-US':
            'Reserves and settles against frozen price versions without holding long DB transactions open during LLM calls.'
        },
        codeRefs: ['V3__run_model_price_bindings.sql', 'RunCostLedgerService.java']
      },
      trace: {
        label: { 'zh-CN': '模型 I/O 落库与玻璃盒', 'en-US': 'Model I/O Glass-Box' },
        role: { 'zh-CN': '透明观测', 'en-US': 'Traceability' },
        tone: 'blue',
        intent: {
          'zh-CN':
            '记录 SYSTEM / USER / ASSISTANT / TOOL 全部消息轮次、耗时与 Token，供复盘 Prompt 效果。',
          'en-US':
            'Persists SYSTEM / USER / ASSISTANT / TOOL turns, latency, and tokens for transparent prompt debugging.'
        },
        codeRefs: ['LlmTracePanel.jsx']
      }
    }
  },
  {
    id: 'delivery-monitoring',
    group: 'governance',
    badge: '08',
    title: {
      'zh-CN': 'Worktree 安全交付与 5 步诊断闭环',
      'en-US': 'Worktree Delivery & 5-Step Diagnostics'
    },
    shortTitle: { 'zh-CN': '交付与监控', 'en-US': 'Delivery & Monitoring' },
    tagline: {
      'zh-CN':
        '代码修改在独立 Git worktree 隔离审查后再提交；运行异常可通过监控看板的 5 步向导一键深链定位到故障玻璃盒。',
      'en-US':
        'Code edits stay isolated in Git worktrees until human approval; failures triage through a 5-step wizard deep-linking straight to the glass-box.'
    },
    defaultNode: 'triage',
    principles: [
      {
        title: { 'zh-CN': '主分支零直接污染', 'en-US': 'Zero Main-Branch Pollution' },
        body: {
          'zh-CN':
            '导入仓库后派生独立 Git worktree，差异审查与提交审批绑定当前代码摘要，绝不盲推。',
          'en-US':
            'Imported repos run in isolated Git worktrees; approval binds to exact commit/file digests before commit or push.'
        }
      },
      {
        title: { 'zh-CN': '多维指标一屏聚合', 'en-US': 'Grafana-Style Telemetry' },
        body: {
          'zh-CN':
            '按时间窗、状态、模型聚合成功率、P95 延迟、Token 与费用趋势，实时掌握系统健康度。',
          'en-US':
            'Aggregates success rate, P95 latency, tokens, and cost trends by range, status, and model.'
        }
      },
      {
        title: { 'zh-CN': '从告警直达根因现场', 'en-US': 'One-Click Deep-Link Triage' },
        body: {
          'zh-CN':
            '5 步排查向导自动扫描终态、异常节点、被拦工具、失败模型与交接，一键跳到对应玻璃盒 Tab。',
          'en-US':
            '5-step diagnostics inspect status, failing nodes, denied tools, model errors, and handoffs with direct deep-links.'
        }
      }
    ],
    nodes: {
      worktree: {
        label: { 'zh-CN': '独立 Git Worktree 沙箱', 'en-US': 'Isolated Git Worktree' },
        role: { 'zh-CN': '代码隔离', 'en-US': 'Code Isolation' },
        tone: 'blue',
        intent: {
          'zh-CN':
            '从受信任本地仓库派生独立分支与工作目录，Agent 的所有授权写入只在隔离副本内发生。',
          'en-US':
            'Spawns a dedicated branch and directory from trusted local repos so all authorized edits stay isolated.'
        },
        codeRefs: ['WorkspaceService.java', 'WorkspacesPage.jsx']
      },
      review: {
        label: { 'zh-CN': 'Diff 审查与摘要复核', 'en-US': 'Diff & Digest Review' },
        role: { 'zh-CN': '人工把关', 'en-US': 'Human Gate' },
        tone: 'amber',
        intent: {
          'zh-CN':
            '读取交付物前复验当前文件 SHA-256 摘要，人工确认改动内容与未跟踪文件后再签发审批。',
          'en-US':
            'Re-verifies SHA-256 digests before previewing; humans inspect diffs and untracked files prior to approval.'
        },
        codeRefs: ['WorkspaceGitPanel.jsx', 'DeliveryStepper.jsx']
      },
      gitCommit: {
        label: { 'zh-CN': '受控本地提交 / 推送合并', 'en-US': 'Controlled Commit / Merge' },
        role: { 'zh-CN': '安全交付', 'en-US': 'Safe Delivery' },
        tone: 'green',
        intent: {
          'zh-CN':
            '先本地 commit，再按需显式准备、批准并执行推送或合并，冲突时自动保留待人工核对态。',
          'en-US':
            'Commits locally first, then optionally prepares and approves push/merge; conflicts pause safely for human review.'
        },
        codeRefs: ['WorkspaceService.java', 'WorkspaceGitPanel.jsx']
      },
      dashboard: {
        label: { 'zh-CN': '细粒度监控看板', 'en-US': 'Monitoring Dashboard' },
        role: { 'zh-CN': '全局观测', 'en-US': 'Observability' },
        tone: 'purple',
        intent: {
          'zh-CN':
            '按 24h / 7d / 30d 聚合运行量、成功率、延迟与模型成本分布，支持状态与模型交叉筛选。',
          'en-US':
            'Aggregates volume, pass rate, latency, and model cost across 24h/7d/30d with status and model filters.'
        },
        codeRefs: ['MonitoringDashboardService.java', 'MonitoringPanel.jsx', 'MonitoringCharts.jsx']
      },
      triage: {
        label: { 'zh-CN': '5 步根因排查向导', 'en-US': '5-Step Triage Wizard' },
        role: { 'zh-CN': '智能诊断', 'en-US': 'Diagnostics' },
        tone: 'amber',
        intent: {
          'zh-CN': '服务端按请求 Locale 实时生成 5 步排查结论，自动标出异常步骤与可执行修复建议。',
          'en-US':
            'Generates localized 5-step diagnostics on the server, flagging failing steps and actionable next actions.'
        },
        codeRefs: ['RunDiagnosticsService.java', 'RunDiagnosticsDrawer.jsx']
      },
      deeplink: {
        label: { 'zh-CN': '深链直达玻璃盒现场', 'en-US': 'Deep-Link to Glass-Box' },
        role: { 'zh-CN': '闭环定位', 'en-US': 'Root-Cause Jump' },
        tone: 'green',
        intent: {
          'zh-CN':
            '携带 ?nodeId=...&glassbox=gateway|llm|handoff 一键跳转到故障节点对应的玻璃盒面板。',
          'en-US':
            'Opens /runs/:id?nodeId=...&glassbox=gateway|llm|handoff to land directly on the failing glass-box tab.'
        },
        codeRefs: ['RunDetailPage.jsx', 'NodeInspector.jsx']
      }
    }
  }
];

export function getArchitectureTopic(topicId) {
  return ARCHITECTURE_TOPICS.find(item => item.id === topicId) ?? ARCHITECTURE_TOPICS[0];
}

export function pickText(value, locale) {
  if (!value) return '';
  if (typeof value === 'string') return value;
  return value[locale] ?? value['zh-CN'] ?? Object.values(value)[0] ?? '';
}
