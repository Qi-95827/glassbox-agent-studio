import { Box } from '@mui/material';
import ArchitectureSubDiagram from './ArchitectureSubDiagrams';
import { ARCHITECTURE_SUB_TOPICS } from './architectureSubCatalog';

const SUB_TOPIC_IDS = new Set(ARCHITECTURE_SUB_TOPICS.map(item => item.id));

/**
 * 架构图谱纯 SVG 交互渲染引擎。
 * 视觉遵循 Google Material 3 浅色高对比语义色板，所有节点支持点击高亮与键盘访问。
 */

const TONES = {
  blue: {
    accent: '#1A73E8',
    soft: '#E8F0FE',
    border: '#AECBFA',
    text: '#174EA6'
  },
  green: {
    accent: '#1E8E3E',
    soft: '#E6F4EA',
    border: '#A8DAB5',
    text: '#137333'
  },
  amber: {
    accent: '#E37400',
    soft: '#FEF7E0',
    border: '#FDE293',
    text: '#B06000'
  },
  purple: {
    accent: '#8430CE',
    soft: '#F3E8FD',
    border: '#D7AEFB',
    text: '#681DA8'
  },
  red: {
    accent: '#D93025',
    soft: '#FCE8E6',
    border: '#F6AEA9',
    text: '#A50E0E'
  }
};

function DiagramDefs({ idPrefix }) {
  return (
    <defs>
      <pattern id={`${idPrefix}-grid`} width="20" height="20" patternUnits="userSpaceOnUse">
        <circle cx="2" cy="2" r="1" fill="#DCE3EC" />
      </pattern>
      <filter id={`${idPrefix}-shadow`} x="-8%" y="-10%" width="116%" height="128%">
        <feDropShadow dx="0" dy="2" stdDeviation="3" floodColor="#1F2937" floodOpacity="0.06" />
      </filter>
      <filter id={`${idPrefix}-glow`} x="-10%" y="-14%" width="120%" height="136%">
        <feDropShadow dx="0" dy="3" stdDeviation="5" floodColor="#1A73E8" floodOpacity="0.18" />
      </filter>
      <marker
        id={`${idPrefix}-arrow`}
        viewBox="0 0 10 10"
        refX="8.5"
        refY="5"
        markerWidth="6.5"
        markerHeight="6.5"
        orient="auto-start-reverse"
      >
        <path d="M 1 1.5 L 8.5 5 L 1 8.5 z" fill="#7B8794" />
      </marker>
      <marker
        id={`${idPrefix}-arrow-green`}
        viewBox="0 0 10 10"
        refX="8.5"
        refY="5"
        markerWidth="6.5"
        markerHeight="6.5"
        orient="auto-start-reverse"
      >
        <path d="M 1 1.5 L 8.5 5 L 1 8.5 z" fill="#1E8E3E" />
      </marker>
      <marker
        id={`${idPrefix}-arrow-red`}
        viewBox="0 0 10 10"
        refX="8.5"
        refY="5"
        markerWidth="6.5"
        markerHeight="6.5"
        orient="auto-start-reverse"
      >
        <path d="M 1 1.5 L 8.5 5 L 1 8.5 z" fill="#D93025" />
      </marker>
      <marker
        id={`${idPrefix}-arrow-blue`}
        viewBox="0 0 10 10"
        refX="8.5"
        refY="5"
        markerWidth="6.5"
        markerHeight="6.5"
        orient="auto-start-reverse"
      >
        <path d="M 1 1.5 L 8.5 5 L 1 8.5 z" fill="#1A73E8" />
      </marker>
    </defs>
  );
}

function ZoneBox({
  x,
  y,
  width,
  height,
  label,
  color = '#94A3B8',
  fill = 'rgba(255,255,255,0.55)'
}) {
  return (
    <g>
      <rect
        x={x}
        y={y}
        width={width}
        height={height}
        rx="16"
        fill={fill}
        stroke={color}
        strokeWidth="1.25"
        strokeDasharray="5 4"
      />
      {label && (
        <text
          x={x + 14}
          y={y + 20}
          fontSize="11"
          fontWeight="600"
          fill="#5F6368"
          letterSpacing="0.02em"
        >
          {label}
        </text>
      )}
    </g>
  );
}

function FlowPath({ idPrefix, d, variant = 'default', dashed = false, label, labelX, labelY }) {
  const color =
    variant === 'green'
      ? '#1E8E3E'
      : variant === 'red'
        ? '#D93025'
        : variant === 'blue'
          ? '#1A73E8'
          : '#80868B';
  const marker =
    variant === 'green'
      ? `url(#${idPrefix}-arrow-green)`
      : variant === 'red'
        ? `url(#${idPrefix}-arrow-red)`
        : variant === 'blue'
          ? `url(#${idPrefix}-arrow-blue)`
          : `url(#${idPrefix}-arrow)`;
  return (
    <g>
      <path
        d={d}
        fill="none"
        stroke={color}
        strokeWidth="1.65"
        strokeDasharray={dashed ? '4 3' : undefined}
        markerEnd={marker}
      />
      {label && labelX != null && labelY != null && (
        <g transform={`translate(${labelX}, ${labelY})`}>
          <rect
            x={-24}
            y={-10}
            width={48}
            height={18}
            rx={9}
            fill={variant === 'red' ? '#FCE8E6' : variant === 'green' ? '#E6F4EA' : '#FFFFFF'}
            stroke={variant === 'red' ? '#F6AEA9' : variant === 'green' ? '#A8DAB5' : '#DADCE0'}
            strokeWidth="1"
          />
          <text x="0" y="3" textAnchor="middle" fontSize="10" fontWeight="700" fill={color}>
            {label}
          </text>
        </g>
      )}
    </g>
  );
}

function DiagramNode({
  idPrefix,
  id,
  x,
  y,
  width,
  height,
  title,
  subtitle,
  badge,
  tone = 'blue',
  selected,
  onSelect
}) {
  const palette = TONES[tone] ?? TONES.blue;
  const centerX = x + width / 2;
  const hasSubtitle = Boolean(subtitle);
  const titleY = hasSubtitle ? y + height / 2 - 4 : y + height / 2 + 4;

  return (
    <g
      role="button"
      tabIndex={0}
      aria-label={title}
      aria-pressed={selected}
      onClick={() => onSelect?.(id)}
      onKeyDown={event => {
        if (event.key === 'Enter' || event.key === ' ') {
          event.preventDefault();
          onSelect?.(id);
        }
      }}
      style={{ cursor: 'pointer', outline: 'none' }}
    >
      <rect
        x={x}
        y={y}
        width={width}
        height={height}
        rx="13"
        fill={selected ? palette.soft : '#FFFFFF'}
        stroke={selected ? palette.accent : '#DADCE0'}
        strokeWidth={selected ? 2 : 1.2}
        filter={selected ? `url(#${idPrefix}-glow)` : `url(#${idPrefix}-shadow)`}
      />
      {/* 顶部语义色细条 */}
      <rect
        x={x + 12}
        y={y + 1.5}
        width={Math.max(24, width - 24)}
        height="3.2"
        rx="1.6"
        fill={palette.accent}
        opacity={selected ? 1 : 0.72}
      />
      {badge && (
        <g transform={`translate(${x + 12}, ${y + 13})`}>
          <rect x="0" y="0" width="22" height="16" rx="8" fill={palette.soft} />
          <text
            x="11"
            y="11.5"
            textAnchor="middle"
            fontSize="9.5"
            fontWeight="700"
            fill={palette.text}
          >
            {badge}
          </text>
        </g>
      )}
      <text
        x={badge && width < 175 ? x + width / 2 + 8 : centerX}
        y={titleY}
        textAnchor="middle"
        fontSize="12.5"
        fontWeight="600"
        fill="#202124"
      >
        {title}
      </text>
      {hasSubtitle && (
        <text
          x={centerX}
          y={y + height / 2 + 15}
          textAnchor="middle"
          fontSize="10.5"
          fill="#5F6368"
        >
          {subtitle}
        </text>
      )}
    </g>
  );
}

function SvgFrame({ idPrefix, viewBox = '0 0 960 360', ariaLabel, children }) {
  return (
    <Box
      sx={{
        width: '100%',
        overflowX: 'auto',
        borderRadius: 3.5,
        border: '1px solid #E0E3EB',
        bgcolor: '#FAFBFD'
      }}
    >
      <svg
        viewBox={viewBox}
        role="img"
        aria-label={ariaLabel}
        style={{
          display: 'block',
          width: '100%',
          minWidth: 740,
          height: 'auto',
          fontFamily: "'Inter', 'Noto Sans SC', -apple-system, BlinkMacSystemFont, sans-serif"
        }}
      >
        <DiagramDefs idPrefix={idPrefix} />
        <rect x="0" y="0" width="100%" height="100%" fill={`url(#${idPrefix}-grid)`} />
        {children}
      </svg>
    </Box>
  );
}

/** 01 · 全栈系统总览 */
function SystemOverviewDiagram({ isEn, selectedId, onSelect }) {
  const p = 'sys';
  return (
    <SvgFrame
      idPrefix={p}
      viewBox="0 0 960 360"
      ariaLabel={isEn ? 'Full-Stack System Overview' : '全栈系统总览'}
    >
      <ZoneBox
        x={238}
        y={22}
        width={482}
        height={316}
        label={
          isEn ? 'SPRING BOOT LOCAL RUNTIME (JAVA 21)' : 'Spring Boot 本地单进程引擎 (Java 21)'
        }
        color="#AECBFA"
        fill="rgba(232,240,254,0.22)"
      />

      {/* 连接线 */}
      <FlowPath idPrefix={p} d="M 196 180 L 258 180" variant="blue" />
      <FlowPath idPrefix={p} d="M 412 155 L 478 88" />
      <FlowPath idPrefix={p} d="M 412 180 L 478 180" variant="blue" />
      <FlowPath idPrefix={p} d="M 594 122 L 594 146" />
      <FlowPath idPrefix={p} d="M 594 214 L 594 246" />
      <FlowPath idPrefix={p} d="M 698 175 L 758 112" variant="blue" />
      <FlowPath idPrefix={p} d="M 698 282 L 758 278" />
      <FlowPath idPrefix={p} d="M 698 192 L 758 258" />

      <DiagramNode
        idPrefix={p}
        id="ui"
        x={24}
        y={136}
        width={172}
        height={88}
        title={isEn ? 'Agent Studio UI' : 'Agent Studio 工作台'}
        subtitle={isEn ? 'React 19 · MUI · Flow' : 'React 19 · 玻璃盒 · 监控'}
        tone="blue"
        selected={selectedId === 'ui'}
        onSelect={onSelect}
      />

      <DiagramNode
        idPrefix={p}
        id="api"
        x={258}
        y={136}
        width={154}
        height={88}
        title={isEn ? 'MVC & Security' : 'Spring MVC 安全边界'}
        subtitle={isEn ? 'Session · CSRF · i18n' : '会话 · CSRF · 归属校验'}
        tone="green"
        selected={selectedId === 'api'}
        onSelect={onSelect}
      />

      <DiagramNode
        idPrefix={p}
        id="planning"
        x={478}
        y={52}
        width={220}
        height={70}
        title={isEn ? 'Planning & Routing' : '任务整理与动态规划'}
        subtitle={isEn ? 'Idea · Route · DAG Validate' : '规则路由 · 候选图校验'}
        tone="blue"
        selected={selectedId === 'planning'}
        onSelect={onSelect}
      />

      <DiagramNode
        idPrefix={p}
        id="orchestration"
        x={478}
        y={146}
        width={220}
        height={68}
        title={isEn ? 'DAG Orchestrator' : 'DAG 运行编排器'}
        subtitle={isEn ? 'Schedule · Handoff · Checkpoints' : '拓扑调度 · 交接 · 检查点'}
        tone="blue"
        selected={selectedId === 'orchestration'}
        onSelect={onSelect}
      />

      <DiagramNode
        idPrefix={p}
        id="agent"
        x={478}
        y={246}
        width={104}
        height={72}
        title={isEn ? 'ReActAgent' : 'AgentScope'}
        subtitle={isEn ? 'Isolated' : '独立装配'}
        tone="purple"
        selected={selectedId === 'agent'}
        onSelect={onSelect}
      />

      <DiagramNode
        idPrefix={p}
        id="gateway"
        x={594}
        y={246}
        width={104}
        height={72}
        title={isEn ? 'Tool Gateway' : '工具安检门'}
        subtitle={isEn ? '6 Gates · MCP' : '六道门 · MCP'}
        tone="amber"
        selected={selectedId === 'gateway'}
        onSelect={onSelect}
      />

      <DiagramNode
        idPrefix={p}
        id="model"
        x={758}
        y={72}
        width={176}
        height={82}
        title={isEn ? 'Models & BYOK' : '受控模型 & BYOK'}
        subtitle={isEn ? 'Demo · AES-256 · Ledger' : '模拟 · 加密密钥 · 账本'}
        tone="purple"
        selected={selectedId === 'model'}
        onSelect={onSelect}
      />

      <DiagramNode
        idPrefix={p}
        id="storage"
        x={758}
        y={224}
        width={176}
        height={94}
        title={isEn ? 'H2 + Git + Docker' : 'H2 库 + Git / Docker'}
        subtitle={isEn ? 'Flyway V18 · Worktree' : 'Flyway V18 · 沙箱验证'}
        tone="green"
        selected={selectedId === 'storage'}
        onSelect={onSelect}
      />
    </SvgFrame>
  );
}

/** 02 · 后端分层架构 & 前端模块架构 */
function LayersStackDiagram({ isEn, subView, selectedId, onSelect }) {
  const p = subView === 'frontend' ? 'fe' : 'be';
  if (subView === 'frontend') {
    return (
      <SvgFrame
        idPrefix={p}
        viewBox="0 0 960 330"
        ariaLabel={isEn ? 'Frontend Module Architecture' : '前端模块架构'}
      >
        <ZoneBox
          x={28}
          y={24}
          width={904}
          height={282}
          label={
            isEn ? 'REACT 19 + VITE + MUI M3 WORKBENCH' : 'React 19 + Vite + MUI M3 前端工作台分层'
          }
          color="#D7AEFB"
          fill="rgba(243,232,253,0.18)"
        />
        <FlowPath idPrefix={p} d="M 244 166 L 284 166" variant="blue" />
        <FlowPath idPrefix={p} d="M 498 166 L 538 166" variant="blue" />
        <FlowPath idPrefix={p} d="M 730 166 L 764 166" variant="blue" />

        <DiagramNode
          idPrefix={p}
          id="fe-shell"
          x={52}
          y={112}
          width={192}
          height={108}
          badge="L1"
          title={isEn ? 'Shell & Router' : 'WorkbenchShell & 路由'}
          subtitle={isEn ? 'HashRouter · AuthGate' : 'AuthGate · 侧边栏 · TopBar'}
          tone="blue"
          selected={selectedId === 'fe-shell'}
          onSelect={onSelect}
        />
        <DiagramNode
          idPrefix={p}
          id="fe-features"
          x={284}
          y={112}
          width={214}
          height={108}
          badge="L2"
          title={isEn ? 'Feature Slices' : 'Features 垂直切片'}
          subtitle={isEn ? 'Runs · GlassBox · Atlas' : '任务 · 玻璃盒 · 监控 · 图谱'}
          tone="purple"
          selected={selectedId === 'fe-features'}
          onSelect={onSelect}
        />
        <DiagramNode
          idPrefix={p}
          id="fe-hooks"
          x={538}
          y={112}
          width={192}
          height={108}
          badge="L3"
          title={isEn ? 'Query Hooks & i18n' : 'SSE / Query & 双语'}
          subtitle={isEn ? 'SSE Stream · zh / en' : 'SSE 流式 · 降级轮询'}
          tone="green"
          selected={selectedId === 'fe-hooks'}
          onSelect={onSelect}
        />
        <DiagramNode
          idPrefix={p}
          id="fe-api"
          x={764}
          y={112}
          width={146}
          height={108}
          badge="L4"
          title={isEn ? 'API Client' : 'API Client'}
          subtitle={isEn ? 'CSRF · Locale' : 'Cookie · CSRF'}
          tone="amber"
          selected={selectedId === 'fe-api'}
          onSelect={onSelect}
        />
      </SvgFrame>
    );
  }

  return (
    <SvgFrame
      idPrefix={p}
      viewBox="0 0 960 360"
      ariaLabel={isEn ? 'Backend Layered Architecture' : '后端分层与 ArchUnit 守卫架构'}
    >
      <ZoneBox
        x={28}
        y={22}
        width={904}
        height={316}
        label={
          isEn
            ? 'ARCHUNIT GUARDED: ZERO TOP-LEVEL CYCLES · CONTROLLER ≠ JPA · AGENTSCOPE QUARANTINED'
            : 'ArchUnit 自动守卫：顶层零环依赖 · Controller 禁碰 JPA · AgentScope SDK 严格隔离'
        }
        color="#A8DAB5"
        fill="rgba(230,244,234,0.18)"
      />

      <FlowPath idPrefix={p} d="M 226 112 L 274 112" variant="blue" />
      <FlowPath idPrefix={p} d="M 520 112 L 574 112" variant="green" />
      <FlowPath idPrefix={p} d="M 396 154 L 396 220" />
      <FlowPath idPrefix={p} d="M 720 154 L 720 220" />
      <FlowPath idPrefix={p} d="M 574 260 L 520 260" dashed />

      <DiagramNode
        idPrefix={p}
        id="be-web"
        x={52}
        y={72}
        width={174}
        height={82}
        badge="01"
        title={isEn ? 'web / Controllers' : 'web / Controller 层'}
        subtitle={isEn ? 'DTO · Auth · Locale' : '参数校验 · DTO 转换'}
        tone="blue"
        selected={selectedId === 'be-web'}
        onSelect={onSelect}
      />

      <DiagramNode
        idPrefix={p}
        id="be-app"
        x={274}
        y={72}
        width={246}
        height={82}
        badge="02"
        title={isEn ? 'Business Services' : '业务服务层 (Orchestration)'}
        subtitle={
          isEn ? 'planning · task · workspace · monitor' : '规划 · 编排 · 交付 · BYOK · 监控'
        }
        tone="blue"
        selected={selectedId === 'be-app'}
        onSelect={onSelect}
      />

      <DiagramNode
        idPrefix={p}
        id="be-framework"
        x={574}
        y={72}
        width={326}
        height={82}
        badge="03"
        title={isEn ? 'domain & framework (Pure)' : 'domain & framework 纯净契约层'}
        subtitle={isEn ? 'Zero Spring / JPA / AgentScope Imports' : '零外部框架依赖 · 纯核心抽象'}
        tone="green"
        selected={selectedId === 'be-framework'}
        onSelect={onSelect}
      />

      <DiagramNode
        idPrefix={p}
        id="be-persistence"
        x={274}
        y={220}
        width={246}
        height={84}
        badge="04"
        title={isEn ? 'store & persistence' : 'store & persistence 层'}
        subtitle={isEn ? 'Spring Data JPA · Flyway V1–V18' : 'JPA Repository · Flyway V1–V18'}
        tone="amber"
        selected={selectedId === 'be-persistence'}
        onSelect={onSelect}
      />

      <DiagramNode
        idPrefix={p}
        id="be-infra"
        x={574}
        y={220}
        width={326}
        height={84}
        badge="05"
        title={isEn ? 'infrastructure (SDK Quarantine)' : 'infrastructure (AgentScope 隔离圈)'}
        subtitle={
          isEn ? 'Only place importing io.agentscope.*' : '唯一允许依赖 AgentScope SDK 与外部探针'
        }
        tone="purple"
        selected={selectedId === 'be-infra'}
        onSelect={onSelect}
      />
    </SvgFrame>
  );
}

/** 03 · 一次运行如何形成（DAG 编排） */
function RunLifecycleDiagram({ isEn, selectedId, onSelect }) {
  const p = 'dag';
  return (
    <SvgFrame
      idPrefix={p}
      viewBox="0 0 960 350"
      ariaLabel={isEn ? 'Run Lifecycle and DAG Orchestration' : '一次运行如何形成与 DAG 编排'}
    >
      <ZoneBox
        x={28}
        y={24}
        width={904}
        height={138}
        label={
          isEn
            ? 'PHASE A · PLAN & FREEZE (READ-ONLY BEFORE CONFIRM)'
            : '阶段 A · 规划与确认冻结（确认前零副作用）'
        }
        color="#AECBFA"
        fill="rgba(232,240,254,0.2)"
      />
      <ZoneBox
        x={28}
        y={184}
        width={904}
        height={142}
        label={
          isEn
            ? 'PHASE B · TOPOLOGICAL EXECUTION, EVIDENCE & RECOVERY'
            : '阶段 B · 拓扑调度、证据关卡与安全恢复'
        }
        color="#A8DAB5"
        fill="rgba(230,244,234,0.2)"
      />

      <FlowPath idPrefix={p} d="M 294 98 L 352 98" variant="blue" />
      <FlowPath idPrefix={p} d="M 608 98 L 666 98" variant="blue" />
      <FlowPath idPrefix={p} d="M 790 138 L 790 166 L 170 166 L 170 218" variant="green" />
      <FlowPath idPrefix={p} d="M 294 256 L 352 256" variant="blue" />
      <FlowPath idPrefix={p} d="M 608 256 L 666 256" variant="green" />

      <DiagramNode
        idPrefix={p}
        id="idea"
        x={48}
        y={60}
        width={246}
        height={76}
        badge="1"
        title={isEn ? 'Idea & Entry Route' : '想法整理与入口路由'}
        subtitle={isEn ? 'Goals · Constraints · Template' : '目标 · 约束 · 模板/规划选择'}
        tone="blue"
        selected={selectedId === 'idea'}
        onSelect={onSelect}
      />
      <DiagramNode
        idPrefix={p}
        id="plan"
        x={352}
        y={60}
        width={256}
        height={76}
        badge="2"
        title={isEn ? 'Candidate & Static Checks' : '候选 DAG 与静态校验'}
        subtitle={isEn ? 'Acyclic · Disjoint Writes · Budget' : '无环 · 写集互斥 · 预算检查'}
        tone="amber"
        selected={selectedId === 'plan'}
        onSelect={onSelect}
      />
      <DiagramNode
        idPrefix={p}
        id="confirm"
        x={666}
        y={60}
        width={246}
        height={76}
        badge="3"
        title={isEn ? 'Human Confirm & Freeze' : '人工确认与事务落库'}
        subtitle={isEn ? 'Atomic Save · Pin Locale & Scope' : '冻结版本 · 权限与语言落库'}
        tone="green"
        selected={selectedId === 'confirm'}
        onSelect={onSelect}
      />
      <DiagramNode
        idPrefix={p}
        id="schedule"
        x={48}
        y={218}
        width={246}
        height={76}
        badge="4"
        title={isEn ? 'DAG Topological Run' : 'DAG 拓扑调度执行'}
        subtitle={isEn ? 'Ready Nodes · Breakpoints · Checks' : '就绪认领 · 教学断点 · 检查'}
        tone="blue"
        selected={selectedId === 'schedule'}
        onSelect={onSelect}
      />
      <DiagramNode
        idPrefix={p}
        id="handoff"
        x={352}
        y={218}
        width={256}
        height={76}
        badge="5"
        title={isEn ? 'Handoff & Evidence Gate' : '交接校验与节点结算'}
        subtitle={isEn ? 'Verify Outputs · Block Missing' : '必需产物核验 · 缺证即阻断'}
        tone="amber"
        selected={selectedId === 'handoff'}
        onSelect={onSelect}
      />
      <DiagramNode
        idPrefix={p}
        id="checkpoint"
        x={666}
        y={218}
        width={246}
        height={76}
        badge="6"
        title={isEn ? 'Checkpoint & Delivery' : '安全检查点与交付'}
        subtitle={isEn ? 'Reports · Spawn Recovery Run' : '可下载产物 · 断点派生恢复'}
        tone="green"
        selected={selectedId === 'checkpoint'}
        onSelect={onSelect}
      />
    </SvgFrame>
  );
}

/** 04 · Tool Gateway 与六道安检门 */
function ToolGatewayDiagram({ isEn, selectedId, onSelect }) {
  const p = 'gw';
  return (
    <SvgFrame
      idPrefix={p}
      viewBox="0 0 960 390"
      ariaLabel={isEn ? 'Tool Gateway and Six Security Gates' : 'Tool Gateway 工具网关与六道安检门'}
    >
      <ZoneBox
        x={196}
        y={24}
        width={738}
        height={292}
        label={
          isEn
            ? 'TOOL GATEWAY · 6 SEQUENTIAL SECURITY GATES (FAIL-CLOSED)'
            : 'Tool Gateway · 六道串联安检门（任一不通过即刻 DENY 拦截）'
        }
        color="#FDE293"
        fill="rgba(254,247,224,0.28)"
      />

      {/* 主链路箭头 */}
      <FlowPath idPrefix={p} d="M 164 110 L 218 110" variant="blue" />
      <FlowPath idPrefix={p} d="M 434 110 L 468 110" variant="green" />
      <FlowPath idPrefix={p} d="M 684 110 L 718 110" variant="green" />
      <FlowPath idPrefix={p} d="M 818 148 L 818 176 L 326 176 L 326 206" variant="green" />
      <FlowPath idPrefix={p} d="M 434 244 L 468 244" variant="green" />
      <FlowPath
        idPrefix={p}
        d="M 684 244 L 718 244"
        variant="green"
        label="PASS"
        labelX={700}
        labelY={228}
      />

      {/* DENY 拦截旁路 */}
      <FlowPath
        idPrefix={p}
        d="M 576 282 L 576 346 L 100 346 L 100 156"
        variant="red"
        dashed
        label="DENY"
        labelX={340}
        labelY={346}
      />

      <DiagramNode
        idPrefix={p}
        id="caller"
        x={24}
        y={68}
        width={140}
        height={84}
        title={isEn ? 'Agent Call' : 'Agent 调用'}
        subtitle={isEn ? 'tool_calls JSON' : 'tool_calls 参数'}
        tone="purple"
        selected={selectedId === 'caller'}
        onSelect={onSelect}
      />

      <DiagramNode
        idPrefix={p}
        id="gate1"
        x={218}
        y={72}
        width={216}
        height={76}
        badge="G1"
        title={isEn ? 'Registry & Contract' : '工具注册与版本'}
        subtitle={isEn ? 'Allowlist & Version Check' : '核对节点允许清单与契约'}
        tone="amber"
        selected={selectedId === 'gate1'}
        onSelect={onSelect}
      />
      <DiagramNode
        idPrefix={p}
        id="gate2"
        x={468}
        y={72}
        width={216}
        height={76}
        badge="G2"
        title={isEn ? 'Cancel / Pause State' : '运行取消与暂停态'}
        subtitle={isEn ? 'Live Human Stop Signal' : '人工停止指令即时生效'}
        tone="amber"
        selected={selectedId === 'gate2'}
        onSelect={onSelect}
      />
      <DiagramNode
        idPrefix={p}
        id="gate3"
        x={718}
        y={72}
        width={196}
        height={76}
        badge="G3"
        title={isEn ? 'Role & Tool Quotas' : '角色与次数配额'}
        subtitle={isEn ? 'Run / Role / Type Limits' : '防止失控刷工具超限'}
        tone="amber"
        selected={selectedId === 'gate3'}
        onSelect={onSelect}
      />

      <DiagramNode
        idPrefix={p}
        id="gate4"
        x={218}
        y={206}
        width={216}
        height={76}
        badge="G4"
        title={isEn ? 'Duplicate Loop Guard' : '重复调用熔断'}
        subtitle={isEn ? 'Break Identical Retries' : '识别死循环并熔断告警'}
        tone="amber"
        selected={selectedId === 'gate4'}
        onSelect={onSelect}
      />
      <DiagramNode
        idPrefix={p}
        id="gate5"
        x={468}
        y={206}
        width={216}
        height={76}
        badge="G5+"
        title={isEn ? 'Scope & HITL 5.5' : '授权复验 & 5.5 审批'}
        subtitle={isEn ? 'Path Norm · High-Risk Pause' : '防穿越 · 高危/MCP 挂起'}
        tone="amber"
        selected={selectedId === 'gate5'}
        onSelect={onSelect}
      />
      <DiagramNode
        idPrefix={p}
        id="gate6"
        x={718}
        y={206}
        width={196}
        height={76}
        badge="G6"
        title={isEn ? 'Execute & Receipt' : '受控执行与回执'}
        subtitle={isEn ? 'SHA-256 & Glass-Box Trace' : '生成摘要回执并留痕'}
        tone="green"
        selected={selectedId === 'gate6'}
        onSelect={onSelect}
      />

      <text x="580" y="366" textAnchor="middle" fontSize="11" fill="#A50E0E" fontWeight="500">
        {isEn
          ? 'Any failed gate returns structured DENY feedback to the model & records evidence'
          : '任意安检门不通过：立即阻断执行、记录 DENY 证据，并将可纠正原因回传给模型'}
      </text>
    </SvgFrame>
  );
}

/** 05 · Agent 装配配方与独立实例 */
function AgentAssemblyDiagram({ isEn, selectedId, onSelect }) {
  const p = 'asm';
  return (
    <SvgFrame
      idPrefix={p}
      viewBox="0 0 960 360"
      ariaLabel={isEn ? 'Agent Assembly Recipe and Isolated Instance' : 'Agent 装配配方与独立实例'}
    >
      <ZoneBox
        x={24}
        y={22}
        width={268}
        height={316}
        label={isEn ? '4 EXPLICIT INGREDIENTS' : '四大显式装配来源'}
        color="#CBD5E1"
      />

      <FlowPath idPrefix={p} d="M 270 80 L 368 158" />
      <FlowPath idPrefix={p} d="M 270 152 L 368 172" />
      <FlowPath idPrefix={p} d="M 270 224 L 368 188" />
      <FlowPath idPrefix={p} d="M 270 294 L 368 202" />
      <FlowPath idPrefix={p} d="M 610 180 L 678 180" variant="blue" />

      <DiagramNode
        idPrefix={p}
        id="prompt"
        x={44}
        y={52}
        width={226}
        height={56}
        title={isEn ? 'Bilingual Role Prompt' : '角色双语 Prompt'}
        subtitle={isEn ? 'prompts/ or prompts/en/' : '按任务 locale 自动加载'}
        tone="blue"
        selected={selectedId === 'prompt'}
        onSelect={onSelect}
      />
      <DiagramNode
        idPrefix={p}
        id="skills"
        x={44}
        y={122}
        width={226}
        height={56}
        title={isEn ? 'Pinned Markdown Skills' : '私有 Markdown Skill'}
        subtitle={isEn ? 'Version-Locked Checklists' : '锁定版本号注入方法论'}
        tone="purple"
        selected={selectedId === 'skills'}
        onSelect={onSelect}
      />
      <DiagramNode
        idPrefix={p}
        id="tools"
        x={44}
        y={192}
        width={226}
        height={56}
        title={isEn ? 'Tool Permission Funnel' : '工具权限漏斗交集'}
        subtitle={isEn ? 'Role ∩ Workspace Scope' : '角色需要 ∩ 工作区授权'}
        tone="amber"
        selected={selectedId === 'tools'}
        onSelect={onSelect}
      />
      <DiagramNode
        idPrefix={p}
        id="context"
        x={44}
        y={262}
        width={226}
        height={56}
        title={isEn ? 'Authorized Context' : '显式授权上下文'}
        subtitle={isEn ? 'Task · Memory · Handoffs' : '任务 · 记忆 · 签收交接'}
        tone="green"
        selected={selectedId === 'context'}
        onSelect={onSelect}
      />

      <DiagramNode
        idPrefix={p}
        id="snapshot"
        x={368}
        y={126}
        width={242}
        height={108}
        badge="SNAP"
        title={isEn ? 'Frozen Assembly Snapshot' : '冻结装配快照'}
        subtitle={isEn ? 'Persisted Before LLM Call' : '调用前落库 · 玻璃盒可查'}
        tone="blue"
        selected={selectedId === 'snapshot'}
        onSelect={onSelect}
      />

      <DiagramNode
        idPrefix={p}
        id="instance"
        x={678}
        y={126}
        width={248}
        height={108}
        badge="RUN"
        title={isEn ? 'Isolated ReActAgent' : '独立 ReActAgent 实例'}
        subtitle={isEn ? 'One Sandbox per Attempt' : '一节点一沙箱 · 用完即毁'}
        tone="purple"
        selected={selectedId === 'instance'}
        onSelect={onSelect}
      />
    </SvgFrame>
  );
}

/** 06 · Handoff Envelope 交接信封与校验管道 */
function HandoffEnvelopeDiagram({ isEn, selectedId, onSelect }) {
  const p = 'ho';
  return (
    <SvgFrame
      idPrefix={p}
      viewBox="0 0 960 350"
      ariaLabel={
        isEn ? 'Handoff Envelope Verification Pipeline' : 'Handoff Envelope 交接信封与校验管道'
      }
    >
      <FlowPath idPrefix={p} d="M 216 146 L 264 146" variant="blue" />
      <FlowPath idPrefix={p} d="M 468 146 L 516 146" variant="blue" />
      <FlowPath
        idPrefix={p}
        d="M 736 122 L 784 96"
        variant="green"
        label="PASS"
        labelX={754}
        labelY={96}
      />
      <FlowPath
        idPrefix={p}
        d="M 736 176 L 784 228"
        variant="red"
        dashed
        label="DENY"
        labelX={754}
        labelY={218}
      />

      <DiagramNode
        idPrefix={p}
        id="upstream"
        x={28}
        y={104}
        width={188}
        height={84}
        badge="1"
        title={isEn ? 'Upstream Output' : '上游节点产出'}
        subtitle={isEn ? 'Result + Write Receipts' : '文本结论 + 写入回执'}
        tone="blue"
        selected={selectedId === 'upstream'}
        onSelect={onSelect}
      />

      <DiagramNode
        idPrefix={p}
        id="extractor"
        x={264}
        y={104}
        width={204}
        height={84}
        badge="2"
        title={isEn ? 'Envelope Extractor' : '结构化信封提取'}
        subtitle={isEn ? 'Summary · Decisions · Risks' : '摘要 · 决策 · 风险 · 产物'}
        tone="purple"
        selected={selectedId === 'extractor'}
        onSelect={onSelect}
      />

      <DiagramNode
        idPrefix={p}
        id="validator"
        x={516}
        y={84}
        width={220}
        height={124}
        badge="5x"
        title={isEn ? '5 Handoff Gates' : '5 项交接校验门禁'}
        subtitle={isEn ? 'Edge · Done · Files · SHA · Run' : '依赖 · 终态 · 产物 · 摘要 · 归属'}
        tone="amber"
        selected={selectedId === 'validator'}
        onSelect={onSelect}
      />

      <DiagramNode
        idPrefix={p}
        id="downstream"
        x={784}
        y={52}
        width={154}
        height={86}
        title={isEn ? 'Inject Downstream' : '注入下游上下文'}
        subtitle={isEn ? 'Trusted Context' : '正式签收投递'}
        tone="green"
        selected={selectedId === 'downstream'}
        onSelect={onSelect}
      />

      <DiagramNode
        idPrefix={p}
        id="block"
        x={784}
        y={194}
        width={154}
        height={86}
        title={isEn ? 'Block Handoff' : '阻断下游执行'}
        subtitle={isEn ? 'OUTPUT_MISSING' : '记录原因待人工'}
        tone="red"
        selected={selectedId === 'block'}
        onSelect={onSelect}
      />
    </SvgFrame>
  );
}

/** 07 · BYOK 加密、受控模型与预算账本 */
function ModelByokDiagram({ isEn, selectedId, onSelect }) {
  const p = 'byok';
  return (
    <SvgFrame
      idPrefix={p}
      viewBox="0 0 960 350"
      ariaLabel={
        isEn ? 'BYOK Encryption and Controlled Model Budget' : 'BYOK 加密、受控模型与预算账本'
      }
    >
      <ZoneBox
        x={28}
        y={24}
        width={412}
        height={146}
        label={isEn ? 'LOCAL SECRET VAULT (NEVER ECHOED)' : '本地加密密钥库（API 永不回显明文）'}
        color="#A8DAB5"
        fill="rgba(230,244,234,0.2)"
      />

      <FlowPath idPrefix={p} d="M 222 104 L 258 104" variant="green" />
      <FlowPath idPrefix={p} d="M 424 104 L 486 156" variant="blue" />
      <FlowPath idPrefix={p} d="M 594 214 L 594 252" />
      <FlowPath idPrefix={p} d="M 706 168 L 758 168" variant="blue" />

      <DiagramNode
        idPrefix={p}
        id="vault"
        x={48}
        y={64}
        width={174}
        height={80}
        badge="KEY"
        title={isEn ? 'AES-256-GCM Vault' : 'AES-256-GCM 密钥库'}
        subtitle={isEn ? '12B IV · Masked UI' : '随机 IV · 掩码展示'}
        tone="green"
        selected={selectedId === 'vault'}
        onSelect={onSelect}
      />

      <DiagramNode
        idPrefix={p}
        id="factory"
        x={258}
        y={64}
        width={166}
        height={80}
        title={isEn ? 'Factory & Probe' : '模型工厂与探针'}
        subtitle={isEn ? 'Owner Check · Test' : '按需解密 · 连通测试'}
        tone="blue"
        selected={selectedId === 'factory'}
        onSelect={onSelect}
      />

      <DiagramNode
        idPrefix={p}
        id="controlled"
        x={486}
        y={122}
        width={220}
        height={92}
        badge="HUB"
        title={isEn ? 'ControlledModel & L0–L3' : 'ControlledModel & 四级压缩'}
        subtitle={isEn ? 'Compaction · SSE Token Stream' : 'L0–L3 上下文 · SSE 流式'}
        tone="purple"
        selected={selectedId === 'controlled'}
        onSelect={onSelect}
      />

      <DiagramNode
        idPrefix={p}
        id="ledger"
        x={468}
        y={252}
        width={256}
        height={74}
        badge="TX"
        title={isEn ? 'Row-Lock Reserve & Settle' : '行锁预留 & 独立事务结算'}
        subtitle={isEn ? 'Frozen Prices · Hard Cost Cap' : '冻结单价 · 多退少补不超支'}
        tone="amber"
        selected={selectedId === 'ledger'}
        onSelect={onSelect}
      />

      <DiagramNode
        idPrefix={p}
        id="trace"
        x={758}
        y={122}
        width={176}
        height={92}
        title={isEn ? 'Model I/O Trace' : '模型 I/O 玻璃盒'}
        subtitle={isEn ? 'Messages · Tokens · ms' : '全量轮次与耗时落库'}
        tone="blue"
        selected={selectedId === 'trace'}
        onSelect={onSelect}
      />
    </SvgFrame>
  );
}

/** 08 · Worktree 安全交付与 5 步诊断闭环 */
function DeliveryMonitoringDiagram({ isEn, selectedId, onSelect }) {
  const p = 'ops';
  return (
    <SvgFrame
      idPrefix={p}
      viewBox="0 0 960 480"
      ariaLabel={
        isEn
          ? 'Worktree Delivery, System Monitoring and Eval Lab'
          : 'Worktree 安全交付、系统监控与评测实验室'
      }
    >
      <ZoneBox
        x={28}
        y={22}
        width={904}
        height={146}
        label={
          isEn ? 'TRACK 1 · GIT WORKTREE SAFE DELIVERY' : '闭环一 · Git Worktree 隔离审查与安全交付'
        }
        color="#A8DAB5"
        fill="rgba(230,244,234,0.18)"
      />
      <ZoneBox
        x={28}
        y={192}
        width={904}
        height={146}
        label={
          isEn
            ? 'TRACK 2 · MONITORING DASHBOARD & 5-STEP TRIAGE'
            : '闭环二 · 细粒度监控看板与 5 步深链排障'
        }
        color="#D7AEFB"
        fill="rgba(243,232,253,0.18)"
      />

      <FlowPath idPrefix={p} d="M 304 100 L 356 100" variant="blue" />
      <FlowPath idPrefix={p} d="M 612 100 L 664 100" variant="green" />
      <FlowPath idPrefix={p} d="M 304 270 L 356 270" variant="blue" />
      <FlowPath idPrefix={p} d="M 612 270 L 664 270" variant="green" />

      <DiagramNode
        idPrefix={p}
        id="worktree"
        x={52}
        y={62}
        width={252}
        height={76}
        badge="1"
        title={isEn ? 'Isolated Git Worktree' : '独立 Git Worktree 沙箱'}
        subtitle={isEn ? 'Separate Branch & Directory' : '隔离分支 · 主分支零污染'}
        tone="blue"
        selected={selectedId === 'worktree'}
        onSelect={onSelect}
      />
      <DiagramNode
        idPrefix={p}
        id="review"
        x={356}
        y={62}
        width={256}
        height={76}
        badge="2"
        title={isEn ? 'Diff & SHA-256 Review' : 'Diff 审查与摘要复核'}
        subtitle={isEn ? 'Human Sign-Off Bound to Digest' : '核对差异后签发人工审批'}
        tone="amber"
        selected={selectedId === 'review'}
        onSelect={onSelect}
      />
      <DiagramNode
        idPrefix={p}
        id="gitCommit"
        x={664}
        y={62}
        width={246}
        height={76}
        badge="3"
        title={isEn ? 'Controlled Commit / Push' : '受控提交 / 推送合并'}
        subtitle={isEn ? 'Local First · Conflict Safe' : '先本地提交 · 冲突保留待核'}
        tone="green"
        selected={selectedId === 'gitCommit'}
        onSelect={onSelect}
      />

      <DiagramNode
        idPrefix={p}
        id="dashboard"
        x={52}
        y={232}
        width={252}
        height={76}
        badge="1"
        title={isEn ? 'Monitoring Dashboard' : 'Grafana 风格监控看板'}
        subtitle={isEn ? 'KPIs · P95 · Model Cost Trends' : '成功率 · P95 延迟 · 成本趋势'}
        tone="purple"
        selected={selectedId === 'dashboard'}
        onSelect={onSelect}
      />
      <DiagramNode
        idPrefix={p}
        id="triage"
        x={356}
        y={232}
        width={256}
        height={76}
        badge="2"
        title={isEn ? '5-Step Triage Wizard' : '5 步根因排查向导'}
        subtitle={isEn ? 'Status · Node · Gate · LLM · Edge' : '服务端按 Locale 实时诊断'}
        tone="amber"
        selected={selectedId === 'triage'}
        onSelect={onSelect}
      />
      <DiagramNode
        idPrefix={p}
        id="deeplink"
        x={664}
        y={232}
        width={246}
        height={76}
        badge="3"
        title={isEn ? 'Deep-Link to Glass-Box' : '深链直达玻璃盒现场'}
        subtitle={isEn ? '?nodeId=...&glassbox=...' : '一键定位故障节点与面板'}
        tone="green"
        selected={selectedId === 'deeplink'}
        onSelect={onSelect}
      />

      <ZoneBox
        x={28}
        y={362}
        width={904}
        height={92}
        label={isEn ? 'TRACK 3 · EVAL LAB (SEPARATE WORKSPACE)' : '独立工作区 · 评测实验室'}
        color="#AECBFA"
        fill="rgba(232,240,254,0.18)"
      />
      <DiagramNode
        idPrefix={p}
        id="evalLab"
        x={52}
        y={384}
        width={856}
        height={52}
        badge="EVAL"
        title={isEn ? 'Eval Lab' : '评测实验室'}
        subtitle={
          isEn
            ? '6 real runners · Mini / Hub / Custom · Hybrid grader · SVG radar · A/B ablation'
            : '6 类真实执行器 · 内置/Hub/自定义 · 双轨裁判 · 六维雷达 · A/B 消融'
        }
        tone="blue"
        selected={selectedId === 'evalLab'}
        onSelect={onSelect}
      />
    </SvgFrame>
  );
}

export default function ArchitectureDiagram({
  topicId,
  subView,
  locale,
  selectedNodeId,
  onSelectNode
}) {
  const isEn = locale === 'en-US';
  switch (topicId) {
    case 'system-overview':
      return (
        <SystemOverviewDiagram isEn={isEn} selectedId={selectedNodeId} onSelect={onSelectNode} />
      );
    case 'layers-stack':
      return (
        <LayersStackDiagram
          isEn={isEn}
          subView={subView}
          selectedId={selectedNodeId}
          onSelect={onSelectNode}
        />
      );
    case 'run-lifecycle':
      return (
        <RunLifecycleDiagram isEn={isEn} selectedId={selectedNodeId} onSelect={onSelectNode} />
      );
    case 'tool-gateway':
      return <ToolGatewayDiagram isEn={isEn} selectedId={selectedNodeId} onSelect={onSelectNode} />;
    case 'agent-assembly':
      return (
        <AgentAssemblyDiagram isEn={isEn} selectedId={selectedNodeId} onSelect={onSelectNode} />
      );
    case 'handoff-envelope':
      return (
        <HandoffEnvelopeDiagram isEn={isEn} selectedId={selectedNodeId} onSelect={onSelectNode} />
      );
    case 'model-byok':
      return <ModelByokDiagram isEn={isEn} selectedId={selectedNodeId} onSelect={onSelectNode} />;
    case 'delivery-monitoring':
      return (
        <DeliveryMonitoringDiagram
          isEn={isEn}
          selectedId={selectedNodeId}
          onSelect={onSelectNode}
        />
      );
    default: {
      if (SUB_TOPIC_IDS.has(topicId)) {
        return (
          <ArchitectureSubDiagram
            topicId={topicId}
            locale={locale}
            selectedNodeId={selectedNodeId}
            onSelectNode={onSelectNode}
          />
        );
      }
      return (
        <SystemOverviewDiagram isEn={isEn} selectedId={selectedNodeId} onSelect={onSelectNode} />
      );
    }
  }
}
