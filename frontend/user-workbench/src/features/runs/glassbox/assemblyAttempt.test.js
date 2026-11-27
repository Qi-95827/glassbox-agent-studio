import { describe, expect, it } from 'vitest';
import { latestAssemblyAttempt } from './assemblyAttempt';

describe('latestAssemblyAttempt', () => {
  it('picks the latest attempt that actually has an assembly snapshot', () => {
    const first = { number: 1, assembly: { components: { tools: {} } } };
    const second = { number: 2, assembly: null };

    expect(latestAssemblyAttempt([first, second])).toBe(first);
  });

  it('returns null when no attempt has an assembly snapshot', () => {
    expect(latestAssemblyAttempt([{ number: 1, assembly: null }])).toBeNull();
    expect(latestAssemblyAttempt(undefined)).toBeNull();
  });
});
