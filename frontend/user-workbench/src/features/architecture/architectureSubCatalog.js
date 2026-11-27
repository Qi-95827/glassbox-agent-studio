/**
 * 架构图谱二级深潜子图元数据（中英双语）。
 * 与主图通过 parentId 形成 C4 渐进式下钻结构。
 */

export const ARCHITECTURE_SUB_TOPICS = [
  {
    id: 'security-persistence',
    parentId: 'layers-stack',
    group: 'overview',
    badge: '02.1',
    title: {
      'zh-CN': '安全边界、归属隔离与 Flyway V1–V18 演进',
      'en-US': 'Security Boundary, Ownership & Flyway V1–V18'
    },
    shortTitle: { 'zh-CN': '安全与持久化', 'en-US': 'Security & Flyway' },
    tagline: {
      'zh-CN':
        '每一个 HTTP 请求必须通过会话认证、CSRF 校验与服务端归属核验；数据库演进全部由不可变 Flyway 脚本接管。',
      'en-US':
        'Every HTTP request clears session auth, CSRF protection, and server-side ownership checks; schema evolution is governed by immutable Flyway migrations.'
    },
    defaultNode: 'ownership',
    principles: [
      {
        title: { 'zh-CN': '服务端硬归属隔离', 'en-US': 'Server-Side Ownership' },
        body: {
          'zh-CN': '普通用户无法越权读取他人运行、工作区、BYOK 密钥、长期记忆或 MCP Server。',
          'en-US':
            'Users can never access another owner’s runs, workspaces, BYOK keys, memories, or MCP servers.'
        }
      },
      {
        title: { 'zh-CN': '不可变迁移历史', 'en-US': 'Immutable Migrations' },
        body: {
          'zh-CN': 'Hibernate 自动建表永久关闭，只增不改 V1–V18 迁移脚本，保证老库平滑升级。',
          'en-US':
            'Hibernate auto-DDL is disabled; append-only V1–V18 migrations guarantee clean upgrades.'
        }
      },
      {
        title: { 'zh-CN': '零明文凭据边界', 'en-US': 'Zero Plaintext Secrets' },
        body: {
          'zh-CN': '模型 API Key 与 MCP 请求头统一走 AES-256-GCM 加密落库，接口永不回显明文。',
          'en-US':
            'Model API keys and MCP headers are encrypted with AES-256-GCM and never echoed in APIs.'
        }
      }
    ],
    nodes: {
      session: {
        label: { 'zh-CN': 'Spring Security 会话与 CSRF', 'en-US': 'Session & CSRF Filter' },
        role: { 'zh-CN': '边界 #1', 'en-US': 'Boundary #1' },
        tone: 'blue',
        intent: {
          'zh-CN':
            '基于 PBKDF2 密码哈希与 HttpOnly Servlet Session 认证，所有写接口强制校验 CSRF Token。',
          'en-US':
            'Authenticates via PBKDF2 passwords and HttpOnly Servlet sessions, enforcing CSRF tokens on all mutating calls.'
        },
        codeRefs: ['SecurityConfiguration.java', 'AuthController.java']
      },
      ownership: {
        label: { 'zh-CN': 'OwnershipService 归属核验', 'en-US': 'OwnershipService Check' },
        role: { 'zh-CN': '边界 #2', 'en-US': 'Boundary #2' },
        tone: 'green',
        intent: {
          'zh-CN':
            '按当前登录账号解析 ExecutionScope，在运行、SSE 流、审批、记忆与 MCP 接口前统一拦截越权访问。',
          'en-US':
            'Resolves ExecutionScope from the active account to block unauthorized access across runs, SSE, approvals, memory, and MCP.'
        },
        codeRefs: ['OwnershipService.java', 'ExecutionScopeResolver.java']
      },
      jpa: {
        label: { 'zh-CN': 'Store & Spring Data JPA', 'en-US': 'Store & Spring Data JPA' },
        role: { 'zh-CN': '数据访问', 'en-US': 'Data Access' },
        tone: 'amber',
        intent: {
          'zh-CN':
            'Controller 严禁直接触碰 Repository（ArchUnit 强制守卫），由 Store 封装细粒度事务读写。',
          'en-US':
            'ArchUnit forbids controllers from touching repositories; fine-grained Stores own transactional persistence.'
        },
        codeRefs: ['ArchitectureGuardTest.java', 'RunEvidenceRecordService.java']
      },
      flyway: {
        label: { 'zh-CN': 'Flyway V1–V18 + H2 文件库', 'en-US': 'Flyway V1–V18 + H2 DB' },
        role: { 'zh-CN': '版本演进', 'en-US': 'Schema Versioning' },
        tone: 'purple',
        intent: {
          'zh-CN':
            'V1–V6 奠定玻璃盒与双语基线；V7 并行反思与上下文、V8 审批单、V9 三层记忆、V10 MCP 注册表、V11 评测批次、V12 Hub 与自定义集、V13 知识库与向量、V14 查询索引、V15 思考链、V16 分支运行、V17 外部 Trace、V18 同任务迭代轮次。',
          'en-US':
            'V1–V6 establish glass-box & i18n; V7 parallel/reflection/context, V8 approvals, V9 layered memory, V10 MCP registry, V11 Eval Lab runs, V12 Hub & custom datasets, V13 knowledge/vector, V14 query indexes, V15 reasoning chains, V16 forked runs, V17 external traces, V18 per-task iteration rounds.'
        },
        codeRefs: [
          'V7__parallel_reflection_and_context.sql',
          'V10__mcp_server_registry.sql',
          'V11__eval_lab_v2.sql',
          'V12__eval_hub_and_datasets.sql',
          'V16__run_forks.sql',
          'V17__external_traces.sql',
          'V18__run_iterations.sql'
        ]
      }
    }
  },
  {
    id: 'parallel-dag',
    parentId: 'run-lifecycle',
    group: 'runtime',
    badge: '03.1',
    title: {
      'zh-CN': '真并行分支调度与写集互斥',
      'en-US': 'Parallel Branch Scheduling & Disjoint Writes'
    },
    shortTitle: { 'zh-CN': '真并行调度', 'en-US': 'Parallel DAG' },
    tagline: {
      'zh-CN':
        '在规划期静态证明写集互斥的前提下，调度器以数据库原子认领并发派发多个就绪分支，兼顾提速与零文件冲突。',
      'en-US':
        'Once planning proves disjoint write sets, the scheduler atomically claims and runs ready branches concurrently without file conflicts.'
    },
    defaultNode: 'claim',
    principles: [
      {
        title: { 'zh-CN': '写集互斥先于并发', 'en-US': 'Disjoint Writes First' },
        body: {
          'zh-CN': '并发执行前在数据库事务内二次复验在跑分支与候选分支的写入路径无交集。',
          'en-US':
            'Re-verifies inside a DB transaction that active and candidate branches share zero write paths.'
        }
      },
      {
        title: { 'zh-CN': '原子认领防重复派发', 'en-US': 'Atomic Claiming' },
        body: {
          'zh-CN': '条件更新 PENDING → RUNNING，任何并发调度 tick 都不会把同一节点派发两次。',
          'en-US':
            'Conditional PENDING → RUNNING updates guarantee no node is ever dispatched twice.'
        }
      },
      {
        title: { 'zh-CN': '托管线程池 + 费用行锁', 'en-US': 'Managed Pool + Cost Locks' },
        body: {
          'zh-CN': '受控上限 max-parallel-nodes=3，走 Spring 托管线程池，并发计费由账本行锁兜底。',
          'en-US':
            'Capped at max-parallel-nodes=3 on Spring’s managed pool while row locks protect concurrent cost ledgers.'
        }
      }
    ],
    nodes: {
      ready: {
        label: { 'zh-CN': '扫描全部就绪节点', 'en-US': 'Scan All Ready Nodes' },
        role: { 'zh-CN': '步骤 1', 'en-US': 'Step 1' },
        tone: 'blue',
        intent: {
          'zh-CN': '筛选出所有前置依赖节点均已 COMPLETED 的待执行节点，而不仅限于取第一个。',
          'en-US':
            'Finds every pending node whose upstream dependencies have all settled as COMPLETED.'
        },
        codeRefs: ['WorkflowScheduler.java']
      },
      claim: {
        label: { 'zh-CN': '写集互斥校验 & 原子认领', 'en-US': 'Disjoint Check & Atomic Claim' },
        role: { 'zh-CN': '并发关卡', 'en-US': 'Concurrency Gate' },
        tone: 'amber',
        intent: {
          'zh-CN':
            '校验与当前运行中节点写集互斥且不超过并行上限（默认 3），在事务内原子置为 RUNNING。',
          'en-US':
            'Verifies zero write overlap with running nodes and concurrency cap (default 3), claiming atomically.'
        },
        codeRefs: ['WorkflowScheduler.java', 'PlanStateRepository.java']
      },
      branchA: {
        label: { 'zh-CN': '并发分支 A (独立 Agent 实例)', 'en-US': 'Parallel Branch A' },
        role: { 'zh-CN': '托管线程 #1', 'en-US': 'Managed Worker #1' },
        tone: 'purple',
        intent: {
          'zh-CN':
            '在 Spring ThreadPoolTaskExecutor 上独立执行，仅授权写入自身声明的文件集合（如 sort.js）。',
          'en-US':
            'Runs on Spring ThreadPoolTaskExecutor with isolated Agent instance and exclusive write scope.'
        },
        codeRefs: ['AsyncConfiguration.java', 'AgentScopeExecutor.java']
      },
      branchB: {
        label: { 'zh-CN': '并发分支 B (独立 Agent 实例)', 'en-US': 'Parallel Branch B' },
        role: { 'zh-CN': '托管线程 #2', 'en-US': 'Managed Worker #2' },
        tone: 'purple',
        intent: {
          'zh-CN': '与分支 A 真正并行推理与工具调用，费用通过行锁账本并发安全扣减。',
          'en-US':
            'Executes truly in parallel with Branch A; concurrent model spend is serialized via row-locked ledgers.'
        },
        codeRefs: ['AsyncConfiguration.java', 'RunCostLedgerService.java']
      },
      join: {
        label: { 'zh-CN': '汇合审查节点 (Fan-In)', 'en-US': 'Fan-In Reviewer' },
        role: { 'zh-CN': '步骤 4', 'en-US': 'Step 4' },
        tone: 'green',
        intent: {
          'zh-CN': '等待所有并行上游结算并通过交接信封校验后，汇合两份结构化证据启动独立审查。',
          'en-US':
            'Waits until all parallel branches settle and pass handoff checks, then reviews both verified envelopes.'
        },
        codeRefs: ['WorkflowExecutionService.java', 'HandoffValidator.java']
      }
    }
  },
  {
    id: 'reflection-loop',
    parentId: 'run-lifecycle',
    group: 'runtime',
    badge: '03.2',
    title: {
      'zh-CN': '有界反思环（Critic-Revision 循环）',
      'en-US': 'Bounded Reflection Loop (Critic-Revision)'
    },
    shortTitle: { 'zh-CN': '有界反思环', 'en-US': 'Reflection Loop' },
    tagline: {
      'zh-CN':
        '审查节点发现缺陷时，不直接宣告失败，而是携带结构化缺陷清单打回上游自动返工；达到上限立刻转人工，绝不死循环。',
      'en-US':
        'When a reviewer finds defects, it returns structured feedback upstream for bounded auto-revision—escalating to humans once maxRevisions is hit.'
    },
    defaultNode: 'coordinator',
    principles: [
      {
        title: { 'zh-CN': '回边严格受限', 'en-US': 'Strict Back-Edge Rules' },
        body: {
          'zh-CN': '规划校验器只允许 REVIEW/CHECK 回指直接上游，且强制 1 ≤ maxRevisions ≤ 5。',
          'en-US':
            'Validator only allows REVIEW/CHECK back-edges to direct upstreams with 1 ≤ maxRevisions ≤ 5.'
        }
      },
      {
        title: { 'zh-CN': '结构化缺陷注入', 'en-US': 'Structured Defect Feedback' },
        body: {
          'zh-CN':
            'ReviewVerdict 拆解 severity、filePath、summary 与 suggestedFix，精准喂给下一轮 Attempt。',
          'en-US':
            'ReviewVerdict extracts severity, filePath, summary, and suggestedFix into the next attempt’s context.'
        }
      },
      {
        title: { 'zh-CN': '超限安全降级人工', 'en-US': 'Safe Human Escalation' },
        body: {
          'zh-CN':
            '达到最大修订轮次仍未通过，自动转 BLOCKED + HUMAN_REVIEW_REQUIRED，防止失控烧钱。',
          'en-US':
            'Exceeding maxRevisions transitions to BLOCKED + HUMAN_REVIEW_REQUIRED to prevent runaway spend.'
        }
      }
    ],
    nodes: {
      worker: {
        label: { 'zh-CN': '上游开发 / 分析节点', 'en-US': 'Upstream Dev / Analysis' },
        role: { 'zh-CN': '执行者 (Attempt #N)', 'en-US': 'Worker (Attempt #N)' },
        tone: 'blue',
        intent: {
          'zh-CN':
            '首轮按任务目标产出结果；被反思环打回后，以 revision_index + 1 接收上轮缺陷并定向修复。',
          'en-US':
            'Produces initial output; when revised, spawns revision_index + 1 with injected critic feedback.'
        },
        codeRefs: ['WorkflowExecutionService.java', 'BuiltInContextContributors.java']
      },
      reviewer: {
        label: { 'zh-CN': '审查节点 & Verdict 解析', 'en-US': 'Reviewer & Verdict Parser' },
        role: { 'zh-CN': '批评者 (Critic)', 'en-US': 'Critic' },
        tone: 'purple',
        intent: {
          'zh-CN':
            '独立实例审查产出，由 ReviewVerdictParser 解析出 APPROVED 或 REVISE_REQUIRED 及 defects[]。',
          'en-US':
            'Independent reviewer outputs APPROVED or REVISE_REQUIRED with structured defects[] parsed by ReviewVerdictParser.'
        },
        codeRefs: ['ReviewVerdictParser.java', 'ReviewVerdict.java']
      },
      coordinator: {
        label: { 'zh-CN': 'ReflectionCoordinator 裁决', 'en-US': 'ReflectionCoordinator' },
        role: { 'zh-CN': '有界控制器', 'en-US': 'Loop Controller' },
        tone: 'amber',
        intent: {
          'zh-CN':
            '核对 currentRevision < maxRevisions：允许则发 REVISION_TRIGGERED 事件并重置上游，否则拦截。',
          'en-US':
            'Checks currentRevision < maxRevisions: either emits REVISION_TRIGGERED for another round or halts.'
        },
        codeRefs: ['ReflectionCoordinator.java', 'RevisionEdge.java']
      },
      approved: {
        label: { 'zh-CN': 'APPROVED · 放行交付', 'en-US': 'APPROVED · Deliver' },
        role: { 'zh-CN': '通过终态', 'en-US': 'Pass Terminal' },
        tone: 'green',
        intent: {
          'zh-CN': '审查通过，流程继续向下游或直接进入可下载交付物与运行报告阶段。',
          'en-US': 'Review passes and the workflow proceeds to final deliverables and run reports.'
        },
        codeRefs: ['RunDeliveryService.java']
      },
      escalate: {
        label: { 'zh-CN': '超限 · 转人工核对', 'en-US': 'Limit Hit · Human Review' },
        role: { 'zh-CN': '安全兜底', 'en-US': 'Escalation' },
        tone: 'red',
        intent: {
          'zh-CN':
            '修订次数已达上限，标记 BLOCKED + HUMAN_REVIEW_REQUIRED，保留全部轮次轨迹供人工决策。',
          'en-US':
            'Stops the loop at maxRevisions, setting BLOCKED + HUMAN_REVIEW_REQUIRED with full revision history.'
        },
        codeRefs: ['ReflectionCoordinator.java', 'PlanCandidateValidator.java']
      }
    }
  },
  {
    id: 'hitl-approval',
    parentId: 'tool-gateway',
    group: 'runtime',
    badge: '04.1',
    title: {
      'zh-CN': 'Gate 5.5 工具级动态审批中断（HITL）',
      'en-US': 'Gate 5.5 Dynamic Tool Approval (HITL)'
    },
    shortTitle: { 'zh-CN': 'Gate 5.5 审批', 'en-US': 'Gate 5.5 HITL' },
    tagline: {
      'zh-CN':
        '高危写入或外部 MCP 写操作不再一刀切拒绝：自动挂起生成审批单，支持批准、改参二次复验、或附理由拒绝回传模型。',
      'en-US':
        'High-risk writes and MCP mutations pause into an approval card: approve as-is, edit args with re-validation, or reject with feedback to the model.'
    },
    defaultNode: 'suspend',
    principles: [
      {
        title: { 'zh-CN': '改参必须二次过门 5', 'en-US': 'Edited Args Re-Checked' },
        body: {
          'zh-CN': '用户修改参数后放行时，强制重新跑 Gate 5 授权交集校验，防止人工改参意外越界。',
          'en-US':
            'Approve-with-edited-args re-runs Gate 5 scope validation so edits can never bypass boundaries.'
        }
      },
      {
        title: { 'zh-CN': '拒绝理由回传模型', 'en-US': 'Rejection Guides Model' },
        body: {
          'zh-CN':
            '人工点击拒绝并写明理由，工具返回 APPROVAL_REJECTED 给 Agent，让模型调整方案继续。',
          'en-US':
            'Rejections return APPROVAL_REJECTED with the human’s reason so the agent can adapt its plan.'
        }
      },
      {
        title: { 'zh-CN': '持久化审批与超时保护', 'en-US': 'Persisted & Bounded Wait' },
        body: {
          'zh-CN':
            '审批单落库 tool_approval_requests，周期性响应取消信号，300s 超时自动过期转人工。',
          'en-US':
            'Stored in tool_approval_requests with cancellation checks and 300s expiry protection.'
        }
      }
    ],
    nodes: {
      risk: {
        label: { 'zh-CN': '风险分级评估 (LOW / MED / HIGH)', 'en-US': 'Risk Policy Assessment' },
        role: { 'zh-CN': '触发条件', 'en-US': 'Trigger' },
        tone: 'amber',
        intent: {
          'zh-CN':
            '门 5 通过后由 ConservativeToolRiskPolicy 判定风险：只读自动放行，敏感路径写入或高危 MCP 触发审批。',
          'en-US':
            'ConservativeToolRiskPolicy classifies calls after Gate 5: read-only passes; sensitive writes or HIGH MCP calls suspend.'
        },
        codeRefs: ['ConservativeToolRiskPolicy.java', 'ToolGateway.java']
      },
      suspend: {
        label: { 'zh-CN': '挂起节点 & 推送审批抽屉', 'en-US': 'Suspend & Push Drawer' },
        role: { 'zh-CN': 'WAITING_TOOL_APPROVAL', 'en-US': 'Suspended State' },
        tone: 'blue',
        intent: {
          'zh-CN':
            '写入持久化审批单，节点置为 WAITING_TOOL_APPROVAL，通过 SSE 实时弹出 ToolApprovalDrawer。',
          'en-US':
            'Persists the request, transitions node to WAITING_TOOL_APPROVAL, and opens ToolApprovalDrawer via SSE.'
        },
        codeRefs: ['ToolApprovalService.java', 'ToolApprovalDrawer.jsx']
      },
      approve: {
        label: { 'zh-CN': '分支 A · 原参数批准放行', 'en-US': 'Path A · Approve' },
        role: { 'zh-CN': 'APPROVE', 'en-US': 'APPROVE' },
        tone: 'green',
        intent: {
          'zh-CN': '用户核对原参数无误后点击批准，工具立刻进入门 6 受控执行并记录审计回执。',
          'en-US':
            'Approves original arguments, proceeding directly to Gate 6 execution and receipt logging.'
        },
        codeRefs: ['ToolApprovalController.java', 'ToolGateway.java']
      },
      edit: {
        label: { 'zh-CN': '分支 B · 改参放行 + 二次门 5', 'en-US': 'Path B · Edit + Re-Check' },
        role: { 'zh-CN': 'EDITED_ARGS', 'en-US': 'EDITED_ARGS' },
        tone: 'purple',
        intent: {
          'zh-CN':
            '用户修正 JSON 参数后提交，ToolGateway 强制执行 EDITED_ARGS_REVALIDATION 二次门 5 复验再执行。',
          'en-US':
            'Runs EDITED_ARGS_REVALIDATION through Gate 5 on user-edited JSON before executing.'
        },
        codeRefs: ['ToolGateway.java', 'ToolApprovalService.java']
      },
      reject: {
        label: { 'zh-CN': '分支 C · 拒绝并回传理由', 'en-US': 'Path C · Reject + Reason' },
        role: { 'zh-CN': 'REJECT', 'en-US': 'REJECT' },
        tone: 'red',
        intent: {
          'zh-CN':
            '不执行工具，将用户填写的拒绝原因封装为结构化 ToolResult 回传模型，引导 Agent 另寻方案。',
          'en-US':
            'Skips execution and returns the human rejection reason as a structured ToolResult to steer the model.'
        },
        codeRefs: ['ToolGateway.java', 'ToolApprovalDrawer.jsx']
      }
    }
  },
  {
    id: 'mcp-governance',
    parentId: 'tool-gateway',
    group: 'runtime',
    badge: '04.2',
    title: {
      'zh-CN': 'MCP 外部工具接入与六道安检门收口',
      'en-US': 'MCP Tool Integration & Gateway Governance'
    },
    shortTitle: { 'zh-CN': 'MCP 安全治理', 'en-US': 'MCP Governance' },
    tagline: {
      'zh-CN':
        '外部 MCP Server 绝不允许模型直连裸奔：凭据加密存储、协议隔离在 infrastructure.mcp，所有调用 100% 穿过六道安检门。',
      'en-US':
        'External MCP servers never bypass governance: secrets are encrypted, transports stay quarantined, and 100% of calls pass through Tool Gateway.'
    },
    defaultNode: 'adapter',
    principles: [
      {
        title: { 'zh-CN': '绝不绕过安全网关', 'en-US': 'Zero Gateway Bypass' },
        body: {
          'zh-CN':
            'McpAgentTool 与内置工具走同一套 ToolGateway，共享节点 failure 停止信号、配额与防死循环。',
          'en-US':
            'McpAgentTool routes through the exact same ToolGateway, sharing failure signals, quotas, and loop guards.'
        }
      },
      {
        title: { 'zh-CN': 'STDIO 子进程沙箱隔离', 'en-US': 'Hardened STDIO Sandbox' },
        body: {
          'zh-CN': '可执行命令白名单校验、清空多余环境变量、丢弃 stderr 防管道死锁、严格超时熔断。',
          'en-US':
            'Enforces command allowlists, minimal env vars, discarded stderr (no deadlock), and strict timeouts.'
        }
      },
      {
        title: { 'zh-CN': '逐工具风险分级控制', 'en-US': 'Per-Tool Risk Controls' },
        body: {
          'zh-CN':
            '探测工具列表后，可在设置页为每个 MCP 工具单独开关启停或标记为 HIGH（强制动态审批）。',
          'en-US':
            'After probing tools/list, users can toggle each MCP tool or mark it HIGH for mandatory approval.'
        }
      }
    ],
    nodes: {
      registry: {
        label: { 'zh-CN': 'MCP Server 注册与探测', 'en-US': 'MCP Registry & Probe' },
        role: { 'zh-CN': 'V10 配置层', 'en-US': 'V10 Registry' },
        tone: 'blue',
        intent: {
          'zh-CN':
            '在设置页注册私有 MCP Server，Headers/Env 经 SecretCipher 加密落库；一键探测缓存 tools/list。',
          'en-US':
            'Registers private MCP servers with AES-256 encrypted headers/env and caches tools/list via probe.'
        },
        codeRefs: ['McpServerService.java', 'McpServerPanel.jsx', 'V10__mcp_server_registry.sql']
      },
      provider: {
        label: { 'zh-CN': 'McpToolProvider 命名空间装配', 'en-US': 'McpToolProvider Binding' },
        role: { 'zh-CN': '权限绑定', 'en-US': 'Scoped Binding' },
        tone: 'purple',
        intent: {
          'zh-CN':
            '按运行归属加载已启用工具，统一命名为 mcp::<serverSlug>::<toolName> 防止与内置工具重名。',
          'en-US':
            'Resolves enabled owner tools as mcp::<serverSlug>::<toolName> to prevent name collisions.'
        },
        codeRefs: ['McpToolProvider.java', 'AgentScopeExecutor.java']
      },
      adapter: {
        label: { 'zh-CN': 'McpAgentTool → ToolGateway', 'en-US': 'McpAgentTool → Gateway' },
        role: { 'zh-CN': '强制收口', 'en-US': 'Mandatory Funnel' },
        tone: 'amber',
        intent: {
          'zh-CN':
            '所有 MCP 调用必须串联通过六道安检门 + Gate 5.5 动态审批，全量参数与结果写入玻璃盒。',
          'en-US':
            'Funneling every MCP call through the 6 gates + Gate 5.5 HITL with full glass-box tracing.'
        },
        codeRefs: ['McpAgentTool.java', 'ToolGateway.java']
      },
      transport: {
        label: { 'zh-CN': 'JSON-RPC 2.0 (STDIO / HTTP)', 'en-US': 'JSON-RPC 2.0 Transports' },
        role: { 'zh-CN': '协议隔离层', 'en-US': 'Transport Layer' },
        tone: 'green',
        intent: {
          'zh-CN':
            '支持本地受控 STDIO（命令白名单 + stderr 丢弃）与远程 Streamable HTTP/SSE，带超时熔断。',
          'en-US':
            'Supports allowlisted STDIO (stderr discarded) and Streamable HTTP/SSE with strict timeouts.'
        },
        codeRefs: ['McpJsonRpcClient.java', 'StdioMcpTransport.java', 'HttpMcpTransport.java']
      }
    }
  },
  {
    id: 'layered-memory',
    parentId: 'agent-assembly',
    group: 'runtime',
    badge: '05.1',
    title: {
      'zh-CN': '三层长期记忆：写入时机、人工采纳与混合召回',
      'en-US': 'Layered Long-Term Memory: Write, Confirm & Recall'
    },
    shortTitle: { 'zh-CN': '三层长期记忆', 'en-US': 'Long-Term Memory' },
    tagline: {
      'zh-CN':
        '区分语义/情景/程序三层记忆：运行结束后自动提炼候选经验，必须人工采纳才生效，装配时按四维混合公式透明召回。',
      'en-US':
        'Separates Semantic, Episodic, and Procedural memory: post-run candidates require human adoption before hybrid-scored recall into assembly.'
    },
    defaultNode: 'scoring',
    principles: [
      {
        title: { 'zh-CN': '候选态防记忆投毒', 'en-US': 'Anti-Poisoning Gate' },
        body: {
          'zh-CN': '自动提炼的情景记忆默认为 CANDIDATE，未点击「采纳」前 100% 不会被任何运行召回。',
          'en-US':
            'Auto-extracted episodic memories start as CANDIDATE and are never injected until explicitly adopted.'
        }
      },
      {
        title: { 'zh-CN': '零重依赖四维混合评分', 'en-US': 'Zero-Infra Hybrid Scoring' },
        body: {
          'zh-CN':
            '0.45 词法 + 0.30 路径重叠 + 0.15 时间衰减 + 0.10 置信度（预留 Embedding SPI）。',
          'en-US':
            '0.45 Lexical + 0.30 PathOverlap + 0.15 Recency + 0.10 Confidence (with optional Embedding SPI).'
        }
      },
      {
        title: { 'zh-CN': '玻璃盒召回溯源', 'en-US': 'Glass-Box Recall Proof' },
        body: {
          'zh-CN': '每条注入记忆的得分与命中理由均记录在装配快照中，点击节点就能看清“为何注入”。',
          'en-US':
            'Every injected memory logs its score and match reason in the assembly snapshot for full audit.'
        }
      }
    ],
    nodes: {
      extract: {
        label: { 'zh-CN': '运行结算 → 提炼情景候选', 'en-US': 'Run Settled → Extract' },
        role: { 'zh-CN': '写入时机 (EPISODIC)', 'en-US': 'Write Trigger' },
        tone: 'purple',
        intent: {
          'zh-CN':
            '任务运行结束时，EpisodicMemoryExtractor 自动提炼 1–3 条避坑/根因经验卡，写入 CANDIDATE 态。',
          'en-US':
            'On run completion, EpisodicMemoryExtractor drafts 1–3 lesson cards in CANDIDATE status.'
        },
        codeRefs: ['EpisodicMemoryExtractor.java', 'V9__layered_long_term_memory.sql']
      },
      confirmMem: {
        label: { 'zh-CN': '工作区人工采纳 / 晋升 Skill', 'en-US': 'Human Adopt / Promote' },
        role: { 'zh-CN': '质量闸门 (PROCEDURAL)', 'en-US': 'Quality Gate' },
        tone: 'amber',
        intent: {
          'zh-CN':
            '用户在工作区记忆面板一键「采纳」转为 ACTIVE（或忽略）；高频经验可一键晋升为私有 Skill 草稿。',
          'en-US':
            'Users adopt CANDIDATE entries to ACTIVE or promote proven lessons into private Skill drafts.'
        },
        codeRefs: [
          'MemoryService.java',
          'WorkspaceMemoryPanel.jsx',
          'PrivateSkillDraftAdapter.java'
        ]
      },
      storeMem: {
        label: { 'zh-CN': '三层 ACTIVE 记忆库', 'en-US': 'ACTIVE 3-Layer Store' },
        role: { 'zh-CN': 'SEMANTIC / EPISODIC', 'en-US': 'Active Store' },
        tone: 'blue',
        intent: {
          'zh-CN':
            '统一存储项目规范（语义）、已采纳经验（情景）与方法论（程序），附带路径模式、置信度与命中计数。',
          'en-US':
            'Stores active Semantic conventions, Episodic lessons, and Procedural guides with path patterns and confidence.'
        },
        codeRefs: ['MemoryRecordEntity.java', 'MemoryType.java']
      },
      scoring: {
        label: { 'zh-CN': '四维混合评分引擎 (Top-5)', 'en-US': '4D Hybrid Scoring (Top-5)' },
        role: { 'zh-CN': '召回算法', 'en-US': 'Retrieval Engine' },
        tone: 'amber',
        intent: {
          'zh-CN':
            '按 0.45 词法 + 0.30 路径 Jaccard + 0.15 时间衰减 + 0.10 置信度打分（可叠加 Embedding SPI），低于 0.25 丢弃。',
          'en-US':
            'Scores 0.45 Lexical + 0.30 Path + 0.15 Recency + 0.10 Confidence (+ optional Embedding SPI), filtering < 0.25.'
        },
        codeRefs: ['MemoryRetrievalService.java', 'MemoryEmbeddingProvider.java']
      },
      inject: {
        label: { 'zh-CN': '注入装配配方 + 玻璃盒溯源', 'en-US': 'Inject & Glass-Box Trace' },
        role: { 'zh-CN': '调用时机', 'en-US': 'Assembly Time' },
        tone: 'green',
        intent: {
          'zh-CN':
            '节点启动装配时注入 Top-5 记忆，并将 memoryId、类型、总分与命中原因展示在「装配配方」面板。',
          'en-US':
            'Injects Top-5 memories at node assembly and renders memoryId, type, score, and reason in the glass-box.'
        },
        codeRefs: ['MemoryAssemblyAdapter.java', 'AssemblyBlueprintPanel.jsx']
      }
    }
  },
  {
    id: 'context-compaction',
    parentId: 'model-byok',
    group: 'governance',
    badge: '07.1',
    title: {
      'zh-CN': '四级分层上下文压缩（L0 → L1 → L2 → L3）',
      'en-US': '4-Tier Context Compaction (L0 → L1 → L2 → L3)'
    },
    shortTitle: { 'zh-CN': '四级上下文压缩', 'en-US': 'Context Compaction' },
    tagline: {
      'zh-CN':
        '按 0% → 60% → 78% → 92% 水位渐进治理上下文：优先卸载旧工具大报文，再做滑动摘要，绝不拆散近期成对工具消息。',
      'en-US':
        'Progressively manages context at 0% → 60% → 78% → 92%: offloads old tool blobs first, then slides summaries while preserving paired tool calls.'
    },
    defaultNode: 'l2',
    principles: [
      {
        title: { 'zh-CN': '永不拆散 tool_use / tool_result', 'en-US': 'Never Split Tool Pairs' },
        body: {
          'zh-CN':
            '压缩时重建带原 callId 的 ToolResultBlock，严格保持成对关系，避免模型接口报 400。',
          'en-US':
            'Rebuilds ToolResultBlock with original callId so tool_use/tool_result pairs never break API contracts.'
        }
      },
      {
        title: { 'zh-CN': '渐进式最小损失', 'en-US': 'Progressive Minimal Loss' },
        body: {
          'zh-CN':
            '60% 先卸旧工具冗余正文，78% 才折叠中间历史，始终锁死系统指令与最近 N 轮完整细节。',
          'en-US':
            'Offloads verbose old tool outputs at 60% before summarizing older turns at 78%, protecting pinned anchors.'
        }
      },
      {
        title: { 'zh-CN': '全程落库与占比可视化', 'en-US': 'Full Compaction Telemetry' },
        body: {
          'zh-CN':
            '触发即写入 CONTEXT_COMPACTED 事件与 ContextBreakdown，在玻璃盒呈现 6 段堆叠占比条。',
          'en-US':
            'Persists CONTEXT_COMPACTED and ContextBreakdown to render the 6-segment ContextMeterBar in glass-box.'
        }
      }
    ],
    nodes: {
      l0: {
        label: { 'zh-CN': 'L0 · 全程分桶观测 (0%+)', 'en-US': 'L0 · 6-Bucket Observe' },
        role: { 'zh-CN': '水位 < 60%', 'en-US': 'Usage < 60%' },
        tone: 'blue',
        intent: {
          'zh-CN':
            '每次模型调用前按 SYSTEM / SKILLS / MEMORY / HANDOFF / TOOL_OUTPUTS / HISTORY 精确估算 Token 占比。',
          'en-US':
            'Estimates tokens across SYSTEM, SKILLS, MEMORY, HANDOFF, TOOL_OUTPUTS, and HISTORY before every LLM call.'
        },
        codeRefs: ['MultiTierContextCompactor.java', 'ContextBreakdown.java', 'ContextMeterBar.jsx']
      },
      l1: {
        label: { 'zh-CN': 'L1 · 旧工具结果卸载 (≥60%)', 'en-US': 'L1 · Tool Offload (≥60%)' },
        role: { 'zh-CN': '轻度修剪', 'en-US': 'Light Pruning' },
        tone: 'green',
        intent: {
          'zh-CN':
            '仅扫描非最近 2 轮且超 1500 字的 TOOL 返回，保留头 200 + 尾 100 字 + 证据指针，保护近期细节。',
          'en-US':
            'Offloads >1500-char tool outputs older than 2 turns to head-200 + tail-100 + reference pointer.'
        },
        codeRefs: ['MultiTierContextCompactor.java', 'ControlledModel.java']
      },
      l2: {
        label: { 'zh-CN': 'L2 · 滑动窗口 + 摘要 (≥78%)', 'en-US': 'L2 · Sliding Summary (≥78%)' },
        role: { 'zh-CN': '核心压缩', 'en-US': 'Core Compaction' },
        tone: 'amber',
        intent: {
          'zh-CN':
            '锁死 Pinned Anchor（系统+任务目标）与最近 4 轮完整成对消息，将中间历史折叠为结构化检查点摘要。',
          'en-US':
            'Pins System/Task anchors and the latest 4 paired turns while folding middle history into a structured summary.'
        },
        codeRefs: ['MultiTierContextCompactor.java', 'CompactionTier.java']
      },
      l3: {
        label: { 'zh-CN': 'L3 · 紧急硬兜底 (≥92%)', 'en-US': 'L3 · Hard Guard (≥92%)' },
        role: { 'zh-CN': '防溢出保护', 'en-US': 'Overflow Guard' },
        tone: 'red',
        intent: {
          'zh-CN':
            '若 L2 后仍逼近窗口上限，按优先级裁剪低价值历史正文并记录告警，保证绝不触发 API 400 崩溃。',
          'en-US':
            'Trims remaining low-priority text if L2 is still near the ceiling, preventing API 400 overflow crashes.'
        },
        codeRefs: ['MultiTierContextCompactor.java', 'ControlledModel.java']
      }
    }
  },
  {
    id: 'sse-streaming',
    parentId: 'model-byok',
    group: 'governance',
    badge: '07.2',
    title: {
      'zh-CN': 'SSE 实时流式透视与断线自动降级',
      'en-US': 'SSE Real-Time Streaming & Polling Fallback'
    },
    shortTitle: { 'zh-CN': 'SSE 实时流式', 'en-US': 'SSE Streaming' },
    tagline: {
      'zh-CN':
        '持久化事件事务提交后实时广播，支持 Last-Event-ID 断线零丢消息；网络代理阻断流式时前端自动静默降级至 1.8s 轮询。',
      'en-US':
        'Persisted events broadcast post-commit with Last-Event-ID replay; if proxies block streaming, the UI silently falls back to 1.8s polling.'
    },
    defaultNode: 'broker',
    principles: [
      {
        title: { 'zh-CN': '事务提交后再推送', 'en-US': 'Post-Commit Broadcast' },
        body: {
          'zh-CN': '持久化事件仅在数据库事务提交后推入 SSE，防止前端收到未提交或回滚的幽灵状态。',
          'en-US':
            'Events publish only after DB commit so the UI never sees uncommitted or rolled-back state.'
        }
      },
      {
        title: { 'zh-CN': '事实与瞬态分离', 'en-US': 'Facts vs. Ephemeral Deltas' },
        body: {
          'zh-CN': 'RUN_EVENT 带单调序号落库可补发；MODEL_DELTA 仅作实时打字机推送，不膨胀数据库。',
          'en-US':
            'RUN_EVENT persists with monotonic IDs for replay; MODEL_DELTA streams transiently without bloating DB.'
        }
      },
      {
        title: { 'zh-CN': '断流自动降级保底', 'en-US': 'Zero-Crash Fallback' },
        body: {
          'zh-CN':
            'EventSource 连续异常 3 次即静默回退至 React Query 1.8s 轮询，弱网环境依然丝滑。',
          'en-US':
            '3 consecutive EventSource errors silently fall back to React Query 1.8s polling.'
        }
      }
    ],
    nodes: {
      producer: {
        label: { 'zh-CN': '运行事件 & 模型增量源', 'en-US': 'Events & Model Deltas' },
        role: { 'zh-CN': '发布源', 'en-US': 'Producers' },
        tone: 'purple',
        intent: {
          'zh-CN':
            'RunEvidenceRecordService 在事务提交后发布 RunEventPersisted；ControlledModel 实时发送 MODEL_DELTA。',
          'en-US':
            'RunEvidenceRecordService publishes RunEventPersisted post-commit; ControlledModel emits live MODEL_DELTA.'
        },
        codeRefs: ['RunEvidenceRecordService.java', 'ControlledModel.java']
      },
      broker: {
        label: { 'zh-CN': 'RunStreamBroker 广播中枢', 'en-US': 'RunStreamBroker Hub' },
        role: { 'zh-CN': 'SSE 引擎', 'en-US': 'SSE Engine' },
        tone: 'blue',
        intent: {
          'zh-CN':
            '管理单运行 SseEmitter 订阅（连接上限 + 15s 心跳 + 终态关闭），收到 Last-Event-ID 先查库补发遗漏事件。',
          'en-US':
            'Manages SseEmitters (cap, 15s heartbeat, terminal close) and replays missed DB events on Last-Event-ID.'
        },
        codeRefs: ['RunStreamBroker.java', 'RunStreamController.java']
      },
      sseClient: {
        label: { 'zh-CN': '主通道 · EventSource 实时订阅', 'en-US': 'Primary · EventSource' },
        role: { 'zh-CN': '低延迟路径', 'en-US': 'Live Path' },
        tone: 'green',
        intent: {
          'zh-CN':
            'useRunExecution 优先建立同源 EventSource，毫秒级刷新节点状态、审批抽屉与时间线。',
          'en-US':
            'useRunExecution subscribes via same-origin EventSource for instant graph, approval, and timeline updates.'
        },
        codeRefs: ['useRunExecution.js']
      },
      fallback: {
        label: { 'zh-CN': '降级通道 · 1.8s 游标轮询', 'en-US': 'Fallback · 1.8s Polling' },
        role: { 'zh-CN': '高可用保底', 'en-US': 'Resilient Fallback' },
        tone: 'amber',
        intent: {
          'zh-CN': '当浏览器不支持或连续失败 3 次时自动切换回游标增量轮询，用户无感知中断。',
          'en-US':
            'Automatically reverts to cursor-based polling after 3 consecutive errors with zero UI disruption.'
        },
        codeRefs: ['useRunExecution.js', 'useRunExecution.test.jsx']
      }
    }
  }
];
