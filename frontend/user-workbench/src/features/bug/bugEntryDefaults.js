/** Bug 快捷入口的默认值和模板兼容规则；纯函数便于验收测试。 */

export const BUG_QUICK_GOAL =
  '修复模拟 Bug MOCK-PY-001：先由固定容器复现失败，再定位并只修改授权文件，回归通过后由独立审查汇总；不要声称未验证的结果。';

/** 从 Bug 面板进入任务表单时的默认状态：写入授权必须由用户重新勾选。 */
export function bugQuickEntryDefaults() {
  return {
    bugId: 'MOCK-PY-001',
    entryKind: 'BUG',
    workflowTemplate: 'bug-fix',
    writeEnabled: false,
    reviewPlan: true,
    goal: BUG_QUICK_GOAL
  };
}

/** 与服务端一致的前端预检；不足时只提示，不代替服务端校验。 */
export function workflowCompatible(template, state) {
  const hasWorkspace = !!state.hasWorkspace;
  const hasBug = !!state.hasBug;
  const writeEnabled = !!state.writeEnabled;
  const memoryEnabled = !!state.memoryEnabled;
  switch (template) {
    case 'dual-analysis':
      return !hasBug && !hasWorkspace;
    case 'workspace-analysis':
      return !hasBug && hasWorkspace && !writeEnabled && !memoryEnabled;
    case 'workspace-change':
      return !hasBug && hasWorkspace && writeEnabled && !memoryEnabled;
    case 'bug-fix':
      return hasBug && hasWorkspace && writeEnabled && !memoryEnabled;
    default:
      return true;
  }
}
