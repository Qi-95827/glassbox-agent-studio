import { describe, expect, it } from 'vitest';
import { dateTime, duration, nodeDuration, oneLine, parseFileScopes, splitLines } from './format';

describe('format helpers', () => {
  it('按秒/分钟格式化耗时', () => {
    expect(duration(12400)).toBe('12.4s');
    expect(duration(45100)).toBe('45.1s');
    expect(duration(64000)).toBe('1m 04s');
    expect(duration(null)).toBe('—');
  });

  it('运行中节点以快照时间计算耗时', () => {
    const start = '2026-11-21T10:00:00.000Z';
    expect(nodeDuration({ startedAt: start, status: 'RUNNING' }, '2026-11-21T10:00:55.800Z')).toBe(
      55800
    );
    expect(nodeDuration({ startedAt: start, status: 'RUNNING' }, null)).toBeNull();
  });

  it('解析任务文件范围并兼容旧单值字段', () => {
    expect(parseFileScopes({ FILE_SCOPES_JSON: '["src/a.js"]' })).toEqual(['src/a.js']);
    expect(parseFileScopes({ FILE_SCOPE: 'src/b.js' })).toEqual(['src/b.js']);
    expect(parseFileScopes({ FILE_SCOPES_JSON: 'not-json' })).toEqual([]);
  });

  it('按行拆分并忽略空白', () => {
    expect(splitLines(' a \n\n b \n')).toEqual(['a', 'b']);
    expect(oneLine('a\n b  c', 3)).toBe('a b…');
    expect(dateTime(null)).toBe('—');
  });
});
