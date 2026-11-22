import { clock, shortId } from '../../../utils/format';

/**
 * 交接校验与 ToolGateway 安检门的教学规则字典。
 *
 * 每个 gate 提供：
 * - purpose: 平台为什么要做这项检查
 * - passRule: 满足什么条件才算 PASS
 * - failRule: 什么情况会被拦截（DENY）或跳过（SKIP）
 */
export const HANDOFF_CHECK_RULES = {
  DEPENDENCY_MATCH: {
    purpose:
      '确保只有 DAG 计划中声明的直接上游节点，才能把结论注入当前节点的上下文，防止无关节点越权传话。',
    passRule: '交接记录携带有效的 dependencyNodeId，且来源节点命中当前节点的计划依赖边。',
    failRule: '非 DAG 依赖边触发时标记 SKIP。'
  },
  ATTEMPT_IDENTITY: {
    purpose: '防止重试或并发场景下把旧尝试、失败尝试或伪造来源的数据混入下游执行上下文。',
    passRule:
      '发送方尝试 ID（senderAttemptId）与接收方尝试 ID（receiverAttemptId）均已绑定服务端持久化记录。',
    failRule: '任一持久化尝试 ID 缺失时标记 DENY。'
  },
  TOOL_EVIDENCE: {
    purpose:
      '让下游 Agent 不只看上游“嘴上说的结论”，还能直接核对上游真实调用工具产出的文件版本与回执证据。',
    passRule: '上游节点在执行期间产生了已结算的工具调用回执，并随交接信封一并附带。',
    failRule: '上游为纯分析/推理节点、未调用工具时标记 SKIP（正常现象，代表纯文本结论交接）。'
  },
  ACCEPTED_RECEIPT: {
    purpose: '证明交接不是“口头计划”，而是已经被平台实际写入下游节点的执行输入并落库留痕。',
    passRule: '下游节点接收该交接记录并写入上下文，服务端落库记录了 acceptedAt 接收时间。',
    failRule: '下游尚未确认接收时标记 DENY。'
  }
};

export const GATEWAY_CHECK_RULES = {
  OWNERSHIP_AND_SCOPE: {
    purpose: '防止跨任务、跨账号或失效实例越权调用工具，确保每次调用都归属于当前合法运行实例。',
    passRule: '当前 AgentRuntime 实例通过服务端归属校验，且绑定的任务与工作区范围有效。',
    failRule: '实例已失效、归属不匹配或越出任务范围时立即拦截（DENY）。'
  },
  TOOL_REGISTRY_GRANT: {
    purpose: '落实“最小权限原则”：节点只能调用装配配方中明确放行的工具与对应契约版本。',
    passRule: '请求的工具类型与契约版本命中当前节点授权工具集（读/写权限与节点声明一致）。',
    failRule: '只读节点尝试写文件，或调用未装配给当前节点的工具时拦截（DENY）。'
  },
  BUDGET_AND_LOOP: {
    purpose: '防止 Agent 陷入死循环反复调同一工具，或无限调用导致 Token/费用失控。',
    passRule: '总工具调用次数、单工具调用次数均未达上限，且相同入参指纹未触发重复循环保护。',
    failRule: '超出预算/调用上限或连续重复相同入参调用时拦截（DENY）。'
  },
  PATH_WHITELIST: {
    purpose:
      '把文件读写严格限制在工作区沙箱与任务授权白名单内，杜绝目录穿越（../）或篡改敏感文件。',
    passRule: '目标路径规范化后位于工作区根目录内，且命中本任务的读/写授权白名单。',
    failRule: '路径越界、包含非法目录跳转或文件不在授权清单中时拦截（DENY），动作不会执行。'
  },
  ARGUMENT_SCHEMA: {
    purpose: '在真正触发外部动作前先做无副作用的参数结构校验，避免畸形入参破坏工作区状态。',
    passRule: '入参 JSON 格式、必填字段及业务约束校验全部通过。',
    failRule: '缺少必填参数或参数违反契约约束时拦截（DENY）。'
  },
  CANCEL_STATE: {
    purpose: '确保用户在界面点击「取消运行」或触发断点暂停后，在途工具调用能够及时刹停。',
    passRule: '执行前最后一次确认运行状态正常，未被用户取消且未处于暂停挂起状态。',
    failRule: '前置校验失败时跳过（SKIP）；若运行已取消/暂停则阻断执行。'
  },
  VERSION_GUARD: {
    purpose: '乐观锁防覆盖：防止 Agent 基于过期文件内容盲目写入，覆盖人工或其它节点的最新改动。',
    passRule: '写入前核对目标文件当前 SHA-256 摘要，与读取时观测到的版本一致。',
    failRule: '文件版本已发生变化时标记 DENY，要求重新读取后再决定是否修改。'
  },
  EXECUTION_GUARD: {
    purpose: '兜底保护执行期间暴露的动态参数边界问题，避免未受控异常直接污染上下文。',
    passRule: '工具执行期所有边界条件均合法。',
    failRule: '执行期触发非法参数异常时拦截（DENY）并向模型返回受控错误。'
  }
};

/** 结合当前交接记录，把后端校验明细润色成更易读的“本次为何通过/跳过”。 */
export function describeHandoffCheckEvidence(check, record) {
  const sender = record?.senderNodeLabel || record?.dependencyNodeLabel || '上游节点';
  const receiver = record?.receiverNodeLabel || '当前节点';
  if (check.gate === 'DEPENDENCY_MATCH' && check.status === 'PASS') {
    return `上游节点「${sender}」命中当前节点「${receiver}」的 DAG 计划依赖边（节点 #${shortId(record?.dependencyNodeId)}）。`;
  }
  if (check.gate === 'ATTEMPT_IDENTITY' && check.status === 'PASS') {
    return `发送实例 #${shortId(record?.senderAgentId)}（尝试 #${shortId(record?.senderAttemptId)}）→ 接收实例 #${shortId(record?.receiverAgentId)}（尝试 #${shortId(record?.receiverAttemptId)}）均已绑定持久化 ID。`;
  }
  if (check.gate === 'TOOL_EVIDENCE') {
    const count = record?.toolEvidence?.length ?? 0;
    if (count > 0) {
      return `随交接信封附带了 ${count} 条已结算工具证据，下游可直接核对产物版本。`;
    }
    return check.detail || '上游节点未调用外部工具，本次为纯文本结论交接（标记 SKIP 属正常情况）。';
  }
  if (check.gate === 'ACCEPTED_RECEIPT' && check.status === 'PASS') {
    return `平台已于 ${clock(record?.acceptedAt) || '执行前'} 将此交接写入「${receiver}」的执行上下文。`;
  }
  return check.detail || '服务端已完成该项校验。';
}
