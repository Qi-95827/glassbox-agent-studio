/** 全站业务字典：只描述后端已存在的状态与类型，不在页面内重复硬编码。 */

export const RUN_STATES = {
  PENDING: '排队中',
  RUNNING: '运行中',
  IN_PROGRESS: '进行中',
  AWAITING_REVIEW: '待验收',
  DELIVERED: '已交付',
  CANCELLING: '正在取消',
  PAUSED: '已暂停',
  COMPLETED: '已完成',
  REUSED: '复用历史结果',
  PASSED: '已通过',
  FAILED: '失败',
  CANCELLED: '已取消',
  LIMITED: '已超限',
  BLOCKED: '待处理',
  INTERRUPTED: '已中断',
  SKIPPED: '未执行',
  READY: '准备就绪',
  GENERATING: '生成中',
  INVALID: '校验未通过',
  STALE: '已过期',
  CONSUMED: '已用于运行',
  DRAFT: '草稿',
  PUBLISHED: '已发布',
  ARCHIVED: '已归档',
  APPROVED: '已批准',
  REJECTED: '已拒绝',
  STARTED: '执行中',
  CONFIRMED: '已确认',
  CONFLICT: '冲突，需核对',
  UNKNOWN: '结果未知，需核对',
  ACTIVE: '活跃',
  DELETED: '已删除',
  NOT_SAVED: '尚未保存',
  SAVED: '已保存'
};

export const STOP_CAUSES = {
  USER_CANCELLED: '用户取消',
  INTERRUPTED: '执行中断',
  TIME_LIMIT: '运行时间上限',
  MODEL_ROUND_LIMIT: '模型调用总轮次上限',
  AGENT_MODEL_LIMIT: '单 Agent 模型调用上限',
  ROLE_MODEL_LIMIT: '角色模型调用上限',
  NODE_MODEL_LIMIT: '节点模型调用上限',
  MODEL_RATE_LIMIT: '模型服务限流',
  TOKEN_USAGE_UNKNOWN: 'Token 用量未知',
  TOKEN_LIMIT: '已报告 Token 上限',
  COST_LIMIT: '费用硬预算不足',
  TOOL_CALL_LIMIT: '工具调用总次数上限',
  AGENT_TOOL_LIMIT: '单 Agent 工具调用上限',
  ROLE_TOOL_LIMIT: '角色工具调用上限',
  NODE_TOOL_LIMIT: '节点工具调用上限',
  PER_TOOL_LIMIT: '单工具调用上限',
  SPECIFIC_TOOL_LIMIT: '指定工具调用上限',
  REPEATED_TOOL_LIMIT: '重复工具调用上限',
  TOOL_EFFECT_UNKNOWN: '工具写入结果未知',
  CHECK_FAILED: '系统检查未通过',
  OUTPUT_MISSING: '缺少必需产出',
  RECOVERY_PRECONDITION_FAILED: '恢复前置条件不满足',
  UNCLASSIFIED_FAILURE: '未分类执行失败'
};

export const ENTRY_KINDS = {
  GENERAL: '通用目标',
  REQUIREMENT: '需求',
  DESIGN: '概要设计',
  CODE: '代码',
  TEST: '测试',
  BUG: '模拟 Bug'
};

export const PLAN_ROLES = {
  ANALYST: '分析 Agent',
  CODER: '开发 Agent',
  REVIEWER: '审查 Agent'
};

export const SKILL_NAMES = {
  ANALYZE_EVIDENCE: '问题分析',
  AUTHORIZED_EDIT: '受限代码修改',
  REVIEW_EVIDENCE: '证据审查'
};

export const TOOL_NAMES = {
  SIMULATED_INSPECTION: '模拟检查工具',
  WORKSPACE_READ: '读取授权文件',
  WORKSPACE_SEARCH: '搜索授权文件',
  WORKSPACE_WRITE: '修改授权文件'
};

export const EVENT_NAMES = {
  TASK_CREATED: '创建任务',
  PLAN_APPROVED: '确认计划',
  RUN_STARTED: '开始运行',
  DELEGATED: '分配子任务',
  AGENT_STARTED: '启动 Agent',
  NODE_RETRY_SCHEDULED: '安排节点重试',
  MODEL_STARTED: '模型调用',
  MODEL_FINISHED: '模型返回',
  TOOL_STARTED: '调用工具',
  TOOL_FINISHED: '工具返回',
  TOOL_REJECTED: '工具输入被拒绝',
  HANDOFF: '结果交接',
  HANDOFF_ACCEPTED: '接收实例启动',
  CHECK_STARTED: '开始系统检查',
  CHECK_FINISHED: '系统检查结果',
  CHECKPOINT_SAVED: '保存检查点',
  CHECKPOINT_SKIPPED: '未生成分析后检查点',
  RUN_COMPLETED: '运行完成',
  CANCEL_REQUESTED: '请求取消',
  PAUSE_REQUESTED: '请求暂停',
  PAUSED: '已暂停',
  RESUMED: '继续执行',
  CANCELLED: '运行取消',
  FAILED: '执行失败',
  LIMITED: '达到限制',
  INTERRUPTED: '运行中断',
  SUPPLEMENT_REQUESTED: '等待补充信息',
  SUPPLEMENT_APPLIED: '补充已应用',
  SUPPLEMENT_REJECTED: '补充被拒绝',
  CONTINUED_AFTER_SUPPLEMENT: '补充后继续',
  RECOVERY_STARTED: '恢复运行',
  MEMORY_LOADED: '装配项目背景',
  TEST_STARTED: '开始测试',
  TEST_FINISHED: '测试结果',
  BLOCKED: '等待处理'
};

export const EVALUATION_SUITES = {
  LOCAL_AGENT_V2: '兼容流程种子集',
  DYNAMIC_WORKFLOW_V1: '动态工作流种子集',
  DYNAMIC_CHECK_V1: '动态检查与阻塞种子集'
};

export const COMPARISON_NAMES = {
  UNCHANGED_PASS: '均通过',
  UNCHANGED_FAIL: '均失败',
  IMPROVED: '失败变为通过',
  REGRESSED: '通过变为失败',
  INCONCLUSIVE: '无法判断'
};

/** 语义色档：低饱和浅底 + 高对比深字，禁止大面积刺眼纯色。 */
export const STATUS_TONES = {
  success: { background: '#E6F4EA', color: '#137333' },
  active: { background: '#E8F0FE', color: '#1967D2' },
  warning: { background: '#FEF7E0', color: '#B06000' },
  error: { background: '#FCE8E6', color: '#C5221F' },
  neutral: { background: '#F1F3F4', color: '#5F6368' },
  info: { background: '#E8F0FE', color: '#1967D2' }
};

const TONE_BY_STATUS = {
  COMPLETED: 'success',
  PASSED: 'success',
  PUBLISHED: 'success',
  CONFIRMED: 'success',
  APPROVED: 'success',
  RUNNING: 'active',
  STARTED: 'active',
  CANCELLING: 'warning',
  PAUSED: 'warning',
  BLOCKED: 'warning',
  INTERRUPTED: 'warning',
  LIMITED: 'warning',
  INVALID: 'warning',
  STALE: 'warning',
  FAILED: 'error',
  REJECTED: 'error',
  CONFLICT: 'error',
  UNKNOWN: 'warning',
  PENDING: 'neutral',
  QUEUED: 'neutral',
  CANCELLED: 'neutral',
  SKIPPED: 'neutral',
  DRAFT: 'neutral',
  ARCHIVED: 'neutral',
  REUSED: 'info',
  GENERATING: 'info',
  READY: 'info',
  CONSUMED: 'success',
  IN_PROGRESS: 'active',
  AWAITING_REVIEW: 'warning',
  DELIVERED: 'success'
};

export function statusTone(value) {
  return TONE_BY_STATUS[value] || 'neutral';
}

export function statusLabel(value) {
  return RUN_STATES[value] || value || '未开始';
}

/** 运行是否仍会变化：决定是否继续轮询。 */
export function isLiveStatus(status) {
  return ['PENDING', 'RUNNING', 'PAUSED', 'CANCELLING'].includes(status);
}

export const CHECK_STATUS_LABELS = {
  RUNNING: '检查中',
  PASSED: '通过',
  FAILED: '未通过',
  BLOCKED: '无法判定',
  INTERRUPTED: '已中断',
  STALE: '已过期'
};

export const MODEL_LABELS = {
  mock: '模拟模型',
  deepseek: 'DeepSeek Flash',
  MOCK: '模拟模型',
  DEEPSEEK: 'DeepSeek Flash',
  'deepseek-flash': 'DeepSeek Flash',
  deepseek_flash: 'DeepSeek Flash'
};

/** 模型家族键：装配编码、调用名称和任务模式统一到同一比较口径。 */
export function modelKey(value) {
  const text = String(value ?? '')
    .trim()
    .toLowerCase();
  if (!text) return '';
  if (text.includes('mock') || text.includes('模拟')) return 'mock';
  if (text.includes('deepseek')) return 'deepseek';
  return text;
}

/** 模型编码映射；缺失或未知编码不伪装成具体模型。 */
export function modelLabel(value, fallback = '未知模型') {
  const text = String(value ?? '').trim();
  if (!text) return fallback;
  return MODEL_LABELS[text] || MODEL_LABELS[text.toLowerCase()] || text;
}

/** 节点显示名：历史计划可能没有 label/key，至少用角色名避免“未命名步骤”。 */
export function nodeTitle(node, fallback = '未命名步骤') {
  return node?.label || node?.key || PLAN_ROLES[node?.role] || fallback;
}
