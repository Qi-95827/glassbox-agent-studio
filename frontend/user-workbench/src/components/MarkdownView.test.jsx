import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import MarkdownView from './MarkdownView';

describe('MarkdownView', () => {
  it('安全渲染标题、列表、行内代码、加粗与代码块', () => {
    const sample = [
      '## 分析结论',
      '',
      '已定位 **`index.html`** 中的问题：',
      '- 缺少音效合成函数',
      '- 需要 `localStorage` 持久化',
      '',
      '```js',
      'const count = 1;',
      '```'
    ].join('\n');

    const { container } = render(<MarkdownView text={sample} />);
    expect(screen.getByText('分析结论')).toBeInTheDocument();
    expect(screen.getByText('index.html')).toBeInTheDocument();
    expect(screen.getByText('localStorage')).toBeInTheDocument();
    expect(screen.getByText('const count = 1;')).toBeInTheDocument();
    expect(container.querySelector('code')).not.toBeNull();
  });

  it('安全渲染 Markdown 表格而非原始管道符', () => {
    const tableSample = [
      '| 检查项 | 结果 |',
      '| --- | --- |',
      '| `index.html` | **空文件** |'
    ].join('\n');

    const { container } = render(<MarkdownView text={tableSample} />);
    expect(container.querySelector('table')).not.toBeNull();
    expect(screen.getByText('检查项')).toBeInTheDocument();
    expect(screen.getByText('index.html')).toBeInTheDocument();
    expect(screen.getByText('空文件')).toBeInTheDocument();
  });
});
