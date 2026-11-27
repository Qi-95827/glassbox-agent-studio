import { describe, expect, it } from 'vitest';
import { architectureRunLink } from './ArchitecturePage';

describe('ArchitecturePage → RunDetailPage 深链参数对齐', () => {
  it('输出运行详情真正读取的 nodeId 与 glassbox 参数', () => {
    const link = architectureRunLink('run-1', 'node-9', 'assembly');
    const url = new URL(link, 'http://localhost');
    expect(url.pathname).toBe('/runs/run-1');
    expect(url.searchParams.get('nodeId')).toBe('node-9');
    expect(url.searchParams.get('glassbox')).toBe('assembly');
    // 旧参数名不再出现在新链接里，避免前后端各读一个字段。
    expect(url.searchParams.get('node')).toBeNull();
  });

  it('缺少节点或页签时不输出空参数', () => {
    expect(architectureRunLink('run-1', null, null)).toBe('/runs/run-1');
    expect(architectureRunLink('run-1', 'node-9')).toBe('/runs/run-1?nodeId=node-9');
  });
});
