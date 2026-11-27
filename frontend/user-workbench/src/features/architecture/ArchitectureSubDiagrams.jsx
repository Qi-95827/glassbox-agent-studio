import { DiagramNode, FlowPath, SvgFrame, ZoneBox } from './DiagramPrimitives';

/** 02.1 · 安全边界、归属隔离与 Flyway V1–V18 演进 */
function SecurityPersistenceDiagram({ isEn, selectedId, onSelect }) {
  const p = 'sec';
  return (
    <SvgFrame
      idPrefix={p}
      viewBox="0 0 960 320"
      ariaLabel={isEn ? 'Security Boundary and Flyway Persistence' : '安全边界、归属隔离与持久化'}
    >
      <ZoneBox
        x={24}
        y={24}
        width={436}
        height={270}
        label={isEn ? 'ZERO-TRUST ACCESS BOUNDARY' : '零信任服务端接入与归属边界'}
        color="#A8DAB5"
        fill="rgba(230,244,234,0.18)"
      />
      <ZoneBox
        x={492}
        y={24}
        width={444}
        height={270}
        label={isEn ? 'PERSISTENCE & IMMUTABLE MIGRATIONS' : 'JPA 事务收口与 Flyway 不可变演进'}
        color="#D7AEFB"
        fill="rgba(243,232,253,0.18)"
      />

      <FlowPath idPrefix={p} d="M 220 160 L 260 160" variant="blue" />
      <FlowPath
        idPrefix={p}
        d="M 442 160 L 516 160"
        variant="green"
        label="AUTH"
        labelX={476}
        labelY={144}
      />
      <FlowPath idPrefix={p} d="M 702 160 L 742 160" variant="blue" />

      <DiagramNode
        idPrefix={p}
        id="session"
        x={44}
        y={114}
        width={176}
        height={92}
        badge="1"
        title={isEn ? 'Session & CSRF' : '会话与 CSRF 校验'}
        subtitle={isEn ? 'PBKDF2 · HttpOnly' : 'PBKDF2 · 防跨站'}
        tone="blue"
        selected={selectedId === 'session'}
        onSelect={onSelect}
      />
      <DiagramNode
        idPrefix={p}
        id="ownership"
        x={260}
        y={114}
        width={182}
        height={92}
        badge="2"
        title={isEn ? 'OwnershipService' : 'OwnershipService'}
        subtitle={isEn ? 'Scope Isolation' : '账号资源硬隔离'}
        tone="green"
        selected={selectedId === 'ownership'}
        onSelect={onSelect}
      />
      <DiagramNode
        idPrefix={p}
        id="jpa"
        x={516}
        y={114}
        width={186}
        height={92}
        badge="3"
        title={isEn ? 'Store & JPA' : 'Store & JPA 仓库'}
        subtitle={isEn ? 'ArchUnit Guarded' : 'Controller 禁碰 DB'}
        tone="amber"
        selected={selectedId === 'jpa'}
        onSelect={onSelect}
      />
      <DiagramNode
        idPrefix={p}
        id="flyway"
        x={742}
        y={114}
        width={176}
        height={92}
        badge="V18"
        title={isEn ? 'Flyway V1–V18' : 'Flyway V1–V18'}
        subtitle={isEn ? 'H2 · No Auto-DDL' : '只增不改 · 平滑升级'}
        tone="purple"
        selected={selectedId === 'flyway'}
        onSelect={onSelect}
      />
    </SvgFrame>
  );
}

/** 03.1 · 真并行分支调度与写集互斥 */
function ParallelDagDiagram({ isEn, selectedId, onSelect }) {
  const p = 'par';
  return (
    <SvgFrame
      idPrefix={p}
      viewBox="0 0 960 350"
      ariaLabel={
        isEn ? 'Parallel Branch Scheduling and Disjoint Writes' : '真并行分支调度与写集互斥'
      }
    >
      <ZoneBox
        x={498}
        y={24}
        width={224}
        height={302}
        label={isEn ? 'SPRING MANAGED POOL (MAX 3)' : 'Spring 托管线程池 (并发 ≤ 3)'}
        color="#D7AEFB"
        fill="rgba(243,232,253,0.18)"
      />

      <FlowPath idPrefix={p} d="M 206 175 L 248 175" variant="blue" />
      <FlowPath idPrefix={p} d="M 458 152 L 518 112" variant="blue" />
      <FlowPath idPrefix={p} d="M 458 198 L 518 242" variant="blue" />
      <FlowPath idPrefix={p} d="M 702 112 L 764 156" variant="green" />
      <FlowPath idPrefix={p} d="M 702 242 L 764 194" variant="green" />

      <DiagramNode
        idPrefix={p}
        id="ready"
        x={28}
        y={132}
        width={178}
        height={86}
        badge="1"
        title={isEn ? 'Ready Queue' : '扫描就绪节点'}
        subtitle={isEn ? 'All Upstreams Done' : '上游全 COMPLETED'}
        tone="blue"
        selected={selectedId === 'ready'}
        onSelect={onSelect}
      />
      <DiagramNode
        idPrefix={p}
        id="claim"
        x={248}
        y={124}
        width={210}
        height={102}
        badge="2"
        title={isEn ? 'Disjoint & Atomic Claim' : '写集互斥 & 原子认领'}
        subtitle={isEn ? 'PENDING → RUNNING' : '无交集 · 防重复派发'}
        tone="amber"
        selected={selectedId === 'claim'}
        onSelect={onSelect}
      />
      <DiagramNode
        idPrefix={p}
        id="branchA"
        x={518}
        y={68}
        width={184}
        height={86}
        badge="T1"
        title={isEn ? 'Parallel Branch A' : '并发分支 A'}
        subtitle={isEn ? 'Exclusive Write Set' : '独占写集 · 独立实例'}
        tone="purple"
        selected={selectedId === 'branchA'}
        onSelect={onSelect}
      />
      <DiagramNode
        idPrefix={p}
        id="branchB"
        x={518}
        y={198}
        width={184}
        height={86}
        badge="T2"
        title={isEn ? 'Parallel Branch B' : '并发分支 B'}
        subtitle={isEn ? 'Row-Locked Spend' : '并行执行 · 行锁计费'}
        tone="purple"
        selected={selectedId === 'branchB'}
        onSelect={onSelect}
      />
      <DiagramNode
        idPrefix={p}
        id="join"
        x={764}
        y={132}
        width={170}
        height={86}
        badge="3"
        title={isEn ? 'Fan-In Reviewer' : '汇合审查节点'}
        subtitle={isEn ? 'Merge Envelopes' : '汇合双份验证证据'}
        tone="green"
        selected={selectedId === 'join'}
        onSelect={onSelect}
      />
    </SvgFrame>
  );
}

/** 03.2 · 有界反思环（Critic-Revision 循环） */
function ReflectionLoopDiagram({ isEn, selectedId, onSelect }) {
  const p = 'ref';
  return (
    <SvgFrame
      idPrefix={p}
      viewBox="0 0 960 370"
      ariaLabel={isEn ? 'Bounded Reflection Loop' : '有界反思环'}
    >
      <FlowPath idPrefix={p} d="M 224 128 L 274 128" variant="blue" />
      <FlowPath idPrefix={p} d="M 488 128 L 538 128" variant="blue" />
      <FlowPath
        idPrefix={p}
        d="M 738 104 L 786 84"
        variant="green"
        label="PASS"
        labelX={758}
        labelY={80}
      />
      <FlowPath
        idPrefix={p}
        d="M 738 156 L 786 218"
        variant="red"
        dashed
        label="MAX"
        labelX={758}
        labelY={202}
      />
      {/* 反思回边 */}
      <FlowPath
        idPrefix={p}
        d="M 638 180 L 638 298 L 128 298 L 128 176"
        variant="blue"
        dashed
        label={isEn ? 'REVISE < MAX' : '打回修订 < 上限'}
        labelX={384}
        labelY={298}
      />

      <DiagramNode
        idPrefix={p}
        id="worker"
        x={32}
        y={84}
        width={192}
        height={88}
        badge="DEV"
        title={isEn ? 'Upstream Worker' : '上游开发 / 分析'}
        subtitle={isEn ? 'Attempt #N + Defects' : '接收缺陷 → Attempt+1'}
        tone="blue"
        selected={selectedId === 'worker'}
        onSelect={onSelect}
      />
      <DiagramNode
        idPrefix={p}
        id="reviewer"
        x={274}
        y={84}
        width={214}
        height={88}
        badge="CRITIC"
        title={isEn ? 'Reviewer & Parser' : '审查节点 & 解析器'}
        subtitle={isEn ? 'ReviewVerdict + defects[]' : '结构化缺陷清单'}
        tone="purple"
        selected={selectedId === 'reviewer'}
        onSelect={onSelect}
      />
      <DiagramNode
        idPrefix={p}
        id="coordinator"
        x={538}
        y={76}
        width={200}
        height={104}
        badge="1..5"
        title={isEn ? 'ReflectionCoordinator' : 'ReflectionCoordinator'}
        subtitle={isEn ? 'Check rev < maxRevisions' : '核对修订次数上限'}
        tone="amber"
        selected={selectedId === 'coordinator'}
        onSelect={onSelect}
      />
      <DiagramNode
        idPrefix={p}
        id="approved"
        x={786}
        y={44}
        width={150}
        height={80}
        title={isEn ? 'APPROVED' : 'APPROVED 放行'}
        subtitle={isEn ? 'Deliver Artifacts' : '进入交付投影'}
        tone="green"
        selected={selectedId === 'approved'}
        onSelect={onSelect}
      />
      <DiagramNode
        idPrefix={p}
        id="escalate"
        x={786}
        y={184}
        width={150}
        height={84}
        title={isEn ? 'Human Review' : '超限转人工'}
        subtitle={isEn ? 'HUMAN_REVIEW' : '防死循环兜底'}
        tone="red"
        selected={selectedId === 'escalate'}
        onSelect={onSelect}
      />
    </SvgFrame>
  );
}

/** 04.1 · Gate 5.5 工具级动态审批中断（HITL） */
function HitlApprovalDiagram({ isEn, selectedId, onSelect }) {
  const p = 'hitl';
  return (
    <SvgFrame
      idPrefix={p}
      viewBox="0 0 960 370"
      ariaLabel={isEn ? 'Gate 5.5 Dynamic Tool Approval' : 'Gate 5.5 工具级动态审批'}
    >
      <ZoneBox
        x={534}
        y={22}
        width={402}
        height={326}
        label={
          isEn ? '3 HUMAN DECISIONS (ToolApprovalDrawer)' : '三种人工决策路径（ToolApprovalDrawer）'
        }
        color="#CBD5E1"
      />

      <FlowPath idPrefix={p} d="M 222 184 L 266 184" variant="blue" />
      <FlowPath idPrefix={p} d="M 488 158 L 558 88" variant="green" />
      <FlowPath idPrefix={p} d="M 488 184 L 558 184" variant="blue" />
      <FlowPath idPrefix={p} d="M 488 210 L 558 282" variant="red" dashed />

      <DiagramNode
        idPrefix={p}
        id="risk"
        x={28}
        y={138}
        width={194}
        height={92}
        badge="5.5"
        title={isEn ? 'Risk Assessment' : '风险分级评估'}
        subtitle={isEn ? 'HIGH Write / MCP' : '敏感写入 / 高危 MCP'}
        tone="amber"
        selected={selectedId === 'risk'}
        onSelect={onSelect}
      />
      <DiagramNode
        idPrefix={p}
        id="suspend"
        x={266}
        y={132}
        width={222}
        height={104}
        badge="WAIT"
        title={isEn ? 'Suspend & Push SSE' : '挂起节点 & 推审批单'}
        subtitle={isEn ? 'WAITING_TOOL_APPROVAL' : '落库审批 · 300s 过期'}
        tone="blue"
        selected={selectedId === 'suspend'}
        onSelect={onSelect}
      />
      <DiagramNode
        idPrefix={p}
        id="approve"
        x={558}
        y={52}
        width={354}
        height={72}
        badge="A"
        title={isEn ? 'APPROVE · Execute Original Args' : '批准放行 · 原参数进入门 6'}
        subtitle={
          isEn ? 'Proceeds directly to Gate 6 + SHA-256 receipt' : '直接放行执行并记录写入回执'
        }
        tone="green"
        selected={selectedId === 'approve'}
        onSelect={onSelect}
      />
      <DiagramNode
        idPrefix={p}
        id="edit"
        x={558}
        y={148}
        width={354}
        height={72}
        badge="B"
        title={isEn ? 'EDIT ARGS · Mandatory Gate 5 Re-Check' : '改参放行 · 强制二次过门 5 复验'}
        subtitle={
          isEn ? 'EDITED_ARGS_REVALIDATION prevents bypass' : '防人工改参越界 · 复验通过再执行'
        }
        tone="purple"
        selected={selectedId === 'edit'}
        onSelect={onSelect}
      />
      <DiagramNode
        idPrefix={p}
        id="reject"
        x={558}
        y={246}
        width={354}
        height={72}
        badge="C"
        title={isEn ? 'REJECT · Return Reason to Model' : '拒绝执行 · 理由回传模型调整方案'}
        subtitle={
          isEn
            ? 'Emits APPROVAL_REJECTED ToolResult to guide Agent'
            : '返回 APPROVAL_REJECTED 引导 Agent 改道'
        }
        tone="red"
        selected={selectedId === 'reject'}
        onSelect={onSelect}
      />
    </SvgFrame>
  );
}

/** 04.2 · MCP 外部工具接入与六道安检门收口 */
function McpGovernanceDiagram({ isEn, selectedId, onSelect }) {
  const p = 'mcp';
  return (
    <SvgFrame
      idPrefix={p}
      viewBox="0 0 960 330"
      ariaLabel={isEn ? 'MCP Integration and Gateway Governance' : 'MCP 外部工具接入与安检门收口'}
    >
      <ZoneBox
        x={28}
        y={24}
        width={904}
        height={280}
        label={
          isEn
            ? 'ALL EXTERNAL MCP CALLS ARE FORCED THROUGH TOOL GATEWAY (ZERO BYPASS)'
            : '外部 MCP 工具 100% 强制收口至 Tool Gateway（严禁模型直连裸奔）'
        }
        color="#FDE293"
        fill="rgba(254,247,224,0.2)"
      />

      <FlowPath idPrefix={p} d="M 234 166 L 274 166" variant="blue" />
      <FlowPath idPrefix={p} d="M 472 166 L 512 166" variant="blue" />
      <FlowPath
        idPrefix={p}
        d="M 712 166 L 752 166"
        variant="green"
        label="PASS"
        labelX={732}
        labelY={150}
      />

      <DiagramNode
        idPrefix={p}
        id="registry"
        x={48}
        y={114}
        width={186}
        height={104}
        badge="1"
        title={isEn ? 'MCP Registry (V10)' : 'MCP 注册与探测'}
        subtitle={isEn ? 'AES-256 · tools/list' : 'AES-256 加密 · 缓存'}
        tone="blue"
        selected={selectedId === 'registry'}
        onSelect={onSelect}
      />
      <DiagramNode
        idPrefix={p}
        id="provider"
        x={274}
        y={114}
        width={198}
        height={104}
        badge="2"
        title={isEn ? 'McpToolProvider' : '命名空间装配'}
        subtitle={isEn ? 'mcp::slug::toolName' : '归属过滤 · 风险标注'}
        tone="purple"
        selected={selectedId === 'provider'}
        onSelect={onSelect}
      />
      <DiagramNode
        idPrefix={p}
        id="adapter"
        x={512}
        y={114}
        width={200}
        height={104}
        badge="3"
        title={isEn ? 'Tool Gateway + 5.5' : '六道门 + 5.5 审批'}
        subtitle={isEn ? 'Shared failure · Quotas' : '共享停止信号 · 配额'}
        tone="amber"
        selected={selectedId === 'adapter'}
        onSelect={onSelect}
      />
      <DiagramNode
        idPrefix={p}
        id="transport"
        x={752}
        y={114}
        width={160}
        height={104}
        badge="4"
        title={isEn ? 'JSON-RPC 2.0' : 'STDIO / HTTP'}
        subtitle={isEn ? 'Allowlist · Timeout' : '白名单 · 防死锁'}
        tone="green"
        selected={selectedId === 'transport'}
        onSelect={onSelect}
      />
    </SvgFrame>
  );
}

/** 05.1 · 三层长期记忆：写入时机、人工采纳与混合召回（双泳道） */
function LayeredMemoryDiagram({ isEn, selectedId, onSelect }) {
  const p = 'mem';
  return (
    <SvgFrame
      idPrefix={p}
      viewBox="0 0 960 380"
      ariaLabel={isEn ? 'Layered Long-Term Memory Write and Recall' : '三层长期记忆写入与混合召回'}
    >
      <ZoneBox
        x={24}
        y={20}
        width={912}
        height={152}
        label={
          isEn
            ? 'LANE 1 · WHEN & HOW MEMORY IS WRITTEN (ANTI-POISONING HUMAN GATE)'
            : '泳道一 · 写入时机与人工质量闸门（未采纳绝不自动生效）'
        }
        color="#D7AEFB"
        fill="rgba(243,232,253,0.18)"
      />
      <ZoneBox
        x={24}
        y={198}
        width={912}
        height={160}
        label={
          isEn
            ? 'LANE 2 · WHEN & HOW MEMORY IS RECALLED (4D HYBRID SCORING + GLASS-BOX PROOF)'
            : '泳道二 · 装配期四维混合评分召回与玻璃盒溯源注入'
        }
        color="#A8DAB5"
        fill="rgba(230,244,234,0.18)"
      />

      {/* 上泳道连线 */}
      <FlowPath
        idPrefix={p}
        d="M 304 102 L 354 102"
        variant="blue"
        label="CANDIDATE"
        labelX={329}
        labelY={86}
      />
      <FlowPath
        idPrefix={p}
        d="M 622 102 L 672 102"
        variant="green"
        label="ACTIVE"
        labelX={647}
        labelY={86}
      />
      {/* 跨泳道连接 */}
      <FlowPath idPrefix={p} d="M 796 144 L 796 184 L 180 184 L 180 236" variant="blue" />
      {/* 下泳道连线 */}
      <FlowPath
        idPrefix={p}
        d="M 488 282 L 548 282"
        variant="green"
        label=">= 0.25"
        labelX={518}
        labelY={266}
      />

      <DiagramNode
        idPrefix={p}
        id="extract"
        x={48}
        y={60}
        width={256}
        height={84}
        badge="1"
        title={isEn ? 'Run Settled → Extract' : '运行结算 → 提炼情景候选'}
        subtitle={isEn ? 'EpisodicMemoryExtractor (1–3)' : '提取 1–3 条避坑/根因经验卡'}
        tone="purple"
        selected={selectedId === 'extract'}
        onSelect={onSelect}
      />
      <DiagramNode
        idPrefix={p}
        id="confirmMem"
        x={354}
        y={60}
        width={268}
        height={84}
        badge="2"
        title={isEn ? 'Human Adopt / Promote Skill' : '人工一键采纳 / 晋升 Skill'}
        subtitle={isEn ? 'Approve to ACTIVE · Procedural' : '防幻觉污染 · 可转私有 Skill'}
        tone="amber"
        selected={selectedId === 'confirmMem'}
        onSelect={onSelect}
      />
      <DiagramNode
        idPrefix={p}
        id="storeMem"
        x={672}
        y={60}
        width={240}
        height={84}
        badge="V9"
        title={isEn ? 'ACTIVE 3-Layer Store' : '三层 ACTIVE 记忆库'}
        subtitle={isEn ? 'Semantic · Episodic · Procedural' : '语义 · 情景 · 程序 + 置信度'}
        tone="blue"
        selected={selectedId === 'storeMem'}
        onSelect={onSelect}
      />

      <DiagramNode
        idPrefix={p}
        id="scoring"
        x={48}
        y={236}
        width={440}
        height={92}
        badge="4D"
        title={isEn ? 'Hybrid Scoring Engine (Top-5)' : '四维混合评分引擎 (Top-5, 阈值 ≥ 0.25)'}
        subtitle={
          isEn
            ? '0.45 Lexical + 0.30 Path + 0.15 Recency + 0.10 Confidence (+ SPI)'
            : '0.45 词法 + 0.30 路径重叠 + 0.15 时间衰减 + 0.10 置信度 (含 SPI)'
        }
        tone="amber"
        selected={selectedId === 'scoring'}
        onSelect={onSelect}
      />
      <DiagramNode
        idPrefix={p}
        id="inject"
        x={548}
        y={236}
        width={364}
        height={92}
        badge="SNAP"
        title={isEn ? 'Inject Assembly + Glass-Box Proof' : '注入装配配方 + 玻璃盒透明溯源'}
        subtitle={
          isEn
            ? 'Logs memoryId, type, score & match reason in AssemblySnapshot'
            : '快照记录每条记忆的得分与命中理由，彻底杜绝黑盒注入'
        }
        tone="green"
        selected={selectedId === 'inject'}
        onSelect={onSelect}
      />
    </SvgFrame>
  );
}

/** 07.1 · 四级分层上下文压缩（0% → 60% → 78% → 92% 水位阶梯图） */
function ContextCompactionDiagram({ isEn, selectedId, onSelect }) {
  const p = 'ctx';
  return (
    <SvgFrame
      idPrefix={p}
      viewBox="0 0 960 360"
      ariaLabel={isEn ? '4-Tier Context Compaction Ladder' : '四级分层上下文压缩水位阶梯图'}
    >
      {/* 顶部水位进度标尺 */}
      <g>
        <rect x="40" y="34" width="880" height="12" rx="6" fill="#E8EAED" />
        <rect x="40" y="34" width="528" height="12" rx="6" fill="#A8DAB5" />
        <rect x="568" y="34" width="158" height="12" fill="#FDE293" />
        <rect x="726" y="34" width="124" height="12" fill="#F9AB00" />
        <rect x="850" y="34" width="70" height="12" rx="6" fill="#F28B82" />
        <text x="40" y="25" fontSize="10.5" fontWeight="700" fill="#174EA6">
          0% (L0)
        </text>
        <text x="548" y="25" fontSize="10.5" fontWeight="700" fill="#137333">
          60% (L1)
        </text>
        <text x="708" y="25" fontSize="10.5" fontWeight="700" fill="#B06000">
          78% (L2)
        </text>
        <text x="844" y="25" fontSize="10.5" fontWeight="700" fill="#A50E0E">
          92% (L3)
        </text>
      </g>

      <FlowPath idPrefix={p} d="M 244 196 L 274 196" variant="blue" />
      <FlowPath idPrefix={p} d="M 474 196 L 504 196" variant="green" />
      <FlowPath idPrefix={p} d="M 704 196 L 734 196" variant="red" />

      <DiagramNode
        idPrefix={p}
        id="l0"
        x={40}
        y={96}
        width={204}
        height={196}
        badge="L0"
        title={isEn ? '6-Bucket Observe' : 'L0 · 全程分桶统计'}
        subtitle={isEn ? 'System · Tool · Memory' : '6 桶精确估算 · 占比条'}
        tone="blue"
        selected={selectedId === 'l0'}
        onSelect={onSelect}
      />
      <DiagramNode
        idPrefix={p}
        id="l1"
        x={274}
        y={96}
        width={200}
        height={196}
        badge="L1 ≥60%"
        title={isEn ? 'Tool Result Offload' : 'L1 · 旧工具卸载'}
        subtitle={isEn ? 'Head 200 + Tail 100' : '保近期 2 轮 · 卸大报文'}
        tone="green"
        selected={selectedId === 'l1'}
        onSelect={onSelect}
      />
      <DiagramNode
        idPrefix={p}
        id="l2"
        x={504}
        y={96}
        width={200}
        height={196}
        badge="L2 ≥78%"
        title={isEn ? 'Sliding Summary' : 'L2 · 滑动窗口摘要'}
        subtitle={isEn ? 'Pin Anchor + 4 Turns' : '锁锚点 + 4 轮成对消息'}
        tone="amber"
        selected={selectedId === 'l2'}
        onSelect={onSelect}
      />
      <DiagramNode
        idPrefix={p}
        id="l3"
        x={734}
        y={96}
        width={186}
        height={196}
        badge="L3 ≥92%"
        title={isEn ? 'Hard Overflow Guard' : 'L3 · 紧急硬保护'}
        subtitle={isEn ? 'Prevent API 400 Crash' : '防溢出崩溃 · 落库告警'}
        tone="red"
        selected={selectedId === 'l3'}
        onSelect={onSelect}
      />

      <text x="480" y="330" textAnchor="middle" fontSize="11" fill="#5F6368" fontWeight="500">
        {isEn
          ? 'Invariant across all tiers: tool_use and tool_result blocks always remain paired with original callId'
          : '全等级铁律：永远保留 Pinned Anchor，且绝不拆散 tool_use 与 tool_result 的成对 callId 关系'}
      </text>
    </SvgFrame>
  );
}

/** 07.2 · SSE 实时流式透视与断线自动降级 */
function SseStreamingDiagram({ isEn, selectedId, onSelect }) {
  const p = 'sse';
  return (
    <SvgFrame
      idPrefix={p}
      viewBox="0 0 960 340"
      ariaLabel={
        isEn ? 'SSE Real-Time Streaming and Polling Fallback' : 'SSE 实时流式透视与断线降级'
      }
    >
      <FlowPath idPrefix={p} d="M 244 170 L 296 170" variant="blue" />
      <FlowPath
        idPrefix={p}
        d="M 548 148 L 620 104"
        variant="green"
        label="200 SSE"
        labelX={584}
        labelY={108}
      />
      <FlowPath
        idPrefix={p}
        d="M 548 192 L 620 238"
        variant="red"
        dashed
        label="3x ERR"
        labelX={584}
        labelY={232}
      />

      <DiagramNode
        idPrefix={p}
        id="producer"
        x={32}
        y={122}
        width={212}
        height={96}
        badge="SRC"
        title={isEn ? 'Events & Deltas' : '事件与模型增量源'}
        subtitle={isEn ? 'Post-Commit · Ephemeral' : '提交后发事件 · 瞬态增量'}
        tone="purple"
        selected={selectedId === 'producer'}
        onSelect={onSelect}
      />
      <DiagramNode
        idPrefix={p}
        id="broker"
        x={296}
        y={114}
        width={252}
        height={112}
        badge="SSE"
        title={isEn ? 'RunStreamBroker' : 'RunStreamBroker 广播中枢'}
        subtitle={isEn ? 'Last-Event-ID · 15s Keepalive' : '断线补发 · 15s 心跳 · 鉴权'}
        tone="blue"
        selected={selectedId === 'broker'}
        onSelect={onSelect}
      />
      <DiagramNode
        idPrefix={p}
        id="sseClient"
        x={620}
        y={56}
        width={308}
        height={88}
        badge="MAIN"
        title={isEn ? 'Primary · EventSource Live' : '主通道 · EventSource 实时订阅'}
        subtitle={
          isEn ? 'Instant graph, approval & token updates' : '毫秒级刷新流程图、审批抽屉与时间线'
        }
        tone="green"
        selected={selectedId === 'sseClient'}
        onSelect={onSelect}
      />
      <DiagramNode
        idPrefix={p}
        id="fallback"
        x={620}
        y={196}
        width={308}
        height={88}
        badge="SAFE"
        title={isEn ? 'Fallback · 1.8s Cursor Polling' : '降级通道 · 1.8s 游标轮询'}
        subtitle={
          isEn ? 'Auto-switch after 3 failures, zero crash' : '代理断流自动静默回退，零崩溃保底'
        }
        tone="amber"
        selected={selectedId === 'fallback'}
        onSelect={onSelect}
      />
    </SvgFrame>
  );
}

export default function ArchitectureSubDiagram({ topicId, locale, selectedNodeId, onSelectNode }) {
  const isEn = locale === 'en-US';
  switch (topicId) {
    case 'security-persistence':
      return (
        <SecurityPersistenceDiagram
          isEn={isEn}
          selectedId={selectedNodeId}
          onSelect={onSelectNode}
        />
      );
    case 'parallel-dag':
      return <ParallelDagDiagram isEn={isEn} selectedId={selectedNodeId} onSelect={onSelectNode} />;
    case 'reflection-loop':
      return (
        <ReflectionLoopDiagram isEn={isEn} selectedId={selectedNodeId} onSelect={onSelectNode} />
      );
    case 'hitl-approval':
      return (
        <HitlApprovalDiagram isEn={isEn} selectedId={selectedNodeId} onSelect={onSelectNode} />
      );
    case 'mcp-governance':
      return (
        <McpGovernanceDiagram isEn={isEn} selectedId={selectedNodeId} onSelect={onSelectNode} />
      );
    case 'layered-memory':
      return (
        <LayeredMemoryDiagram isEn={isEn} selectedId={selectedNodeId} onSelect={onSelectNode} />
      );
    case 'context-compaction':
      return (
        <ContextCompactionDiagram isEn={isEn} selectedId={selectedNodeId} onSelect={onSelectNode} />
      );
    case 'sse-streaming':
      return (
        <SseStreamingDiagram isEn={isEn} selectedId={selectedNodeId} onSelect={onSelectNode} />
      );
    default:
      return null;
  }
}
