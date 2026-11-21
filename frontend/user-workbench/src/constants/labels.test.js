import { describe, expect, it } from 'vitest';
import { modelKey, modelLabel, nodeTitle } from './labels';

describe('model identity', () => {
  it('装配编码与调用名称归一到同一模型', () => {
    expect(modelKey('DEEPSEEK')).toBe('deepseek');
    expect(modelKey('deepseek-flash')).toBe('deepseek');
    expect(modelKey('mock')).toBe('mock');
    expect(modelKey('MOCK')).toBe('mock');
    expect(new Set(['DEEPSEEK', 'deepseek-flash'].map(modelKey)).size).toBe(1);
  });

  it('显示实际模型且缺失时不伪装', () => {
    expect(modelLabel('deepseek-flash')).toBe('DeepSeek Flash');
    expect(modelLabel('DEEPSEEK')).toBe('DeepSeek Flash');
    expect(modelLabel('')).toBe('未知模型');
  });

  it('没有计划标签的旧节点回退角色名', () => {
    expect(nodeTitle({ role: 'ANALYST' })).toBe('分析 Agent');
    expect(nodeTitle({ role: 'REVIEWER' })).toBe('审查 Agent');
  });
});
