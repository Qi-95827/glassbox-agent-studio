import { describe, expect, it } from 'vitest';
import { bugQuickEntryDefaults, workflowCompatible } from './bugEntryDefaults';

describe('bug entry defaults', () => {
  it('从 Bug 面板进入时不自动授予写入权限', () => {
    const defaults = bugQuickEntryDefaults();
    expect(defaults.bugId).toBe('MOCK-PY-001');
    expect(defaults.writeEnabled).toBe(false);
    expect(defaults.reviewPlan).toBe(true);
    expect(defaults.entryKind).toBe('BUG');
  });

  it('按工作区、Bug 与写入授权判断工作流兼容性', () => {
    expect(workflowCompatible('dual-analysis', {})).toBe(true);
    expect(
      workflowCompatible('workspace-change', {
        hasWorkspace: true,
        writeEnabled: true
      })
    ).toBe(true);
    expect(
      workflowCompatible('bug-fix', {
        hasWorkspace: true,
        hasBug: true,
        writeEnabled: false
      })
    ).toBe(false);
  });
});
