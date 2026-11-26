import '@testing-library/jest-dom/vitest';
import { afterEach } from 'vitest';
import { cleanup } from '@testing-library/react';

// jsdom 没有 ResizeObserver：React Flow 的画布容器依赖它测量尺寸。
// 这里提供最小实现，让画布类组件可以在单测中挂载；不参与任何生产逻辑。
if (typeof globalThis.ResizeObserver === 'undefined') {
  globalThis.ResizeObserver = class ResizeObserver {
    observe() {}
    unobserve() {}
    disconnect() {}
  };
}

afterEach(() => {
  cleanup();
});
