import test from 'node:test';
import assert from 'node:assert/strict';
import { bugQuickEntryDefaults, workflowCompatible } from './bugEntryDefaults.js';

test('Bug 快捷入口默认不授予写权限并要求先审阅计划', () => {
  const defaults = bugQuickEntryDefaults();
  assert.equal(defaults.writeEnabled, false);
  assert.equal(defaults.reviewPlan, true);
  assert.equal(defaults.workflowTemplate, 'bug-fix');
  assert.equal(defaults.entryKind, 'BUG');
  assert.equal(defaults.bugId, 'MOCK-PY-001');
});

test('Bug 修复模板在显式勾选写权限前不兼容，勾选后才允许创建', () => {
  const base = {
    hasWorkspace: true,
    hasBug: true,
    memoryEnabled: false,
    entryKind: 'BUG'
  };
  assert.equal(workflowCompatible('bug-fix', { ...base, writeEnabled: false }), false);
  assert.equal(workflowCompatible('bug-fix', { ...base, writeEnabled: true }), true);
  assert.equal(
    workflowCompatible('bug-fix', { ...base, writeEnabled: true, memoryEnabled: true }),
    false
  );
});
