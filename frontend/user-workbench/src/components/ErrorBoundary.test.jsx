import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import ErrorBoundary from './ErrorBoundary';
import { setModuleLocale } from '../i18n';

/** 由外部变量控制是否抛错，便于验证「修好之后点重试能恢复」。 */
let shouldThrow = false;

function Boom() {
  if (shouldThrow) throw new Error('render exploded');
  return <div>recovered content</div>;
}

describe('ErrorBoundary', () => {
  beforeEach(() => {
    setModuleLocale('zh-CN');
    shouldThrow = false;
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('正常子树原样渲染，不显示降级界面', () => {
    render(
      <ErrorBoundary scope="canvas">
        <Boom />
      </ErrorBoundary>
    );
    expect(screen.getByText('recovered content')).toBeInTheDocument();
    expect(screen.queryByTestId('error-boundary-fallback')).toBeNull();
  });

  it('子树抛错时只降级该区域，并显示真实错误信息', () => {
    // React 会把边界捕获的错误同时打到 console；测试里静音，避免污染输出。
    const spy = vi.spyOn(console, 'error').mockImplementation(() => {});
    shouldThrow = true;
    render(
      <ErrorBoundary scope="canvas">
        <Boom />
      </ErrorBoundary>
    );
    const fallback = screen.getByTestId('error-boundary-fallback');
    expect(fallback).toHaveAttribute('data-scope', 'canvas');
    expect(fallback).toHaveTextContent('局部渲染失败');
    expect(fallback).toHaveTextContent('render exploded');
    expect(spy).toHaveBeenCalled();
  });

  it('点击重试后重新渲染子树并恢复内容', () => {
    vi.spyOn(console, 'error').mockImplementation(() => {});
    shouldThrow = true;
    render(
      <ErrorBoundary scope="drawer">
        <Boom />
      </ErrorBoundary>
    );
    expect(screen.getByTestId('error-boundary-fallback')).toBeInTheDocument();

    shouldThrow = false;
    fireEvent.click(screen.getByRole('button', { name: '重试' }));

    expect(screen.getByText('recovered content')).toBeInTheDocument();
    expect(screen.queryByTestId('error-boundary-fallback')).toBeNull();
  });

  it('resetKeys 变化时自动复位并回调 onReset', () => {
    vi.spyOn(console, 'error').mockImplementation(() => {});
    const onReset = vi.fn();
    shouldThrow = true;
    const { rerender } = render(
      <ErrorBoundary scope="canvas" resetKeys={['run-a']} onReset={onReset}>
        <Boom />
      </ErrorBoundary>
    );
    expect(screen.getByTestId('error-boundary-fallback')).toBeInTheDocument();
    expect(onReset).not.toHaveBeenCalled();

    rerender(
      <ErrorBoundary scope="canvas" resetKeys={['run-b']} onReset={onReset}>
        <Boom />
      </ErrorBoundary>
    );
    expect(onReset).toHaveBeenCalledTimes(1);
  });

  it('onError 收到原始异常与组件栈', () => {
    vi.spyOn(console, 'error').mockImplementation(() => {});
    const onError = vi.fn();
    shouldThrow = true;
    render(
      <ErrorBoundary scope="global" onError={onError}>
        <Boom />
      </ErrorBoundary>
    );
    expect(onError).toHaveBeenCalledTimes(1);
    expect(onError.mock.calls[0][0].message).toBe('render exploded');
    expect(onError.mock.calls[0][1]).toHaveProperty('componentStack');
  });
});
