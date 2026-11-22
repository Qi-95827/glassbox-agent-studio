import { describe, expect, it } from 'vitest';
import {
  connectionValue,
  modelSelectionLabel,
  parseModelValue,
  selectedConnection
} from './modelConnections';

const list = {
  connections: [{ id: 'c1', displayName: '我的 DeepSeek', modelName: 'deepseek-chat' }]
};

describe('模型连接选择值', () => {
  it('在 mock 与用户连接之间安全转换', () => {
    expect(connectionValue(null)).toBe('mock');
    expect(connectionValue({ id: 'c1' })).toBe('user:c1');
    expect(parseModelValue('mock')).toEqual({ modelMode: 'mock', modelConnectionId: null });
    expect(parseModelValue('user:c1')).toEqual({
      modelMode: 'user',
      modelConnectionId: 'c1'
    });
    expect(parseModelValue('deepseek')).toEqual({
      modelMode: 'deepseek',
      modelConnectionId: null
    });
  });

  it('按当前列表解析展示名，不泄漏密钥', () => {
    expect(selectedConnection(list, 'user:c1')?.id).toBe('c1');
    expect(selectedConnection(list, 'user:missing')).toBeNull();
    expect(modelSelectionLabel(list, 'user:c1')).toBe('我的 DeepSeek');
    expect(modelSelectionLabel(list, 'mock')).toBe('模拟模型（免 Key）');
  });
});
