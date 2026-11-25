import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { I18nProvider } from '../../i18n/I18nProvider';
import { translate } from '../../i18n';
import EvalCaseDetailDrawer, { formatMetricValue } from './EvalCaseDetailDrawer';

const item = {
  id: 'case-1',
  category: 'TOOL_MCP',
  sampleId: 'tool-01',
  titleZh: '授权读取',
  titleEn: 'Authorized read',
  repeatIndex: 1,
  passed: true,
  codeScore: 1,
  costUsd: 0,
  durationMs: 120,
  linkedRunId: 'run-linked-1',
  rawOutput: 'read ok',
  metrics: { selectionAccuracy: 1, gatewayPassRate: 0.457, usageKnown: true },
  assertions: [
    { type: 'TOOL_CALLED', expected: 'workspace_read', actual: ['workspace_read'], passed: true }
  ],
  judgeVerdict: { mode: 'DISABLED' },
  trace: {
    prompt: '读取授权的 src/app.js',
    setup: { authorizedPaths: ['src/app.js'] },
    toolCalls: [
      {
        name: 'workspace_read',
        status: 'SUCCEEDED',
        path: 'src/app.js',
        checks: [
          {
            gate: 'PATH_WHITELIST',
            label: '路径白名单与读写隔离',
            status: 'PASS',
            detail: '目标路径命中只读授权范围'
          }
        ]
      }
    ]
  }
};

describe('评测题玻璃盒穿透抽屉', () => {
  it('展示题目、双语断言、人性化指标并支持深链跳运行玻璃盒', async () => {
    const onOpenRun = vi.fn();
    render(
      <I18nProvider initialLocale="zh-CN">
        <EvalCaseDetailDrawer
          open
          item={item}
          categoryLabel={{ TOOL_MCP: '工具 / Tools' }}
          onClose={() => {}}
          onOpenRun={onOpenRun}
        />
      </I18nProvider>
    );

    expect(screen.getByText('授权读取')).toBeInTheDocument();
    expect(screen.getByText(/工具 \/ Tools/)).toBeInTheDocument();
    expect(screen.getByText('工具已被调用')).toBeInTheDocument();
    expect(screen.getByText(/目标路径命中只读授权范围/)).toBeInTheDocument();
    expect(screen.getByText(/read ok/)).toBeInTheDocument();
    expect(screen.getByText('工具选择准确率')).toBeInTheDocument();
    expect(screen.getByText('100%')).toBeInTheDocument();
    expect(screen.getByText('45.7%')).toBeInTheDocument();
    // 内部调试字段不再出现在指标卡中。
    expect(screen.queryByText('usageKnown')).not.toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: /在运行玻璃盒中打开/ }));
    expect(onOpenRun).toHaveBeenCalledWith('run-linked-1');
  });

  it('英文界面使用英文指标名与 Yes/No，不出现代码变量名', () => {
    const bundle = {
      'common.yes': 'Yes',
      'common.no': 'No',
      'evalLab.metric.compactionEnabled': 'Compaction enabled',
      'evalLab.metric.beforeTokens': 'Est. tokens before',
      'evalLab.metric.answerF1': 'Answer F1'
    };
    const t = key => bundle[key] ?? translate('en-US', key);
    expect(formatMetricValue('compactionEnabled', true, 'en-US', t)).toBe('Yes');
    expect(formatMetricValue('compactionEnabled', false, 'en-US', t)).toBe('No');
    expect(formatMetricValue('beforeTokens', 1226, 'en-US', t)).toBe('1,226');
    expect(formatMetricValue('answerF1', 0.457, 'en-US', t)).toBe('45.7%');
    expect(formatMetricValue('answerF1', 1, 'en-US', t)).toBe('100%');
    expect(formatMetricValue('compressionSavedPercent', 94.51, 'zh-CN', t)).toBe('94.5%');
  });

  it('L2 压缩检查点与摘要内容相同时不重复渲染摘要框', () => {
    const checkpoint = '【上下文压缩检查点】\n## confirmed_facts\n- fact 1';
    render(
      <I18nProvider initialLocale="zh-CN">
        <EvalCaseDetailDrawer
          open
          item={{
            ...item,
            category: 'CONTEXT_COMPACTION',
            metrics: { beforeTokens: 10000, afterTokens: 549, compressionSavedPercent: 94.51 },
            trace: {
              measurement: 'byok_compaction_agent',
              prompt: '长文问题',
              compactionTier: 'L2_SLIDING_SUMMARY',
              summaryPreview: checkpoint,
              structuredAnchors: checkpoint
            }
          }}
          categoryLabel={{ CONTEXT_COMPACTION: '四级上下文压缩' }}
          onClose={() => {}}
        />
      </I18nProvider>
    );

    expect(screen.getByText('94.5%')).toBeInTheDocument();
    expect(screen.getByText('L2 结构化锚点检查点')).toBeInTheDocument();
    expect(screen.queryByText('压缩摘要或卸载摘录')).not.toBeInTheDocument();
  });

  it('用例执行失败时展示真实失败原因，不再呈现空白抽屉', () => {
    render(
      <I18nProvider initialLocale="zh-CN">
        <EvalCaseDetailDrawer
          open
          item={{
            ...item,
            category: 'CODING_REFLECTION',
            passed: false,
            codeScore: 0,
            rawOutput: '',
            assertions: [],
            metrics: { executionFailed: true },
            trace: { prompt: '修复加法', errorCode: '评测工作区创建失败。' }
          }}
          categoryLabel={{ CODING_REFLECTION: '端到端代码与有界反思' }}
          onClose={() => {}}
        />
      </I18nProvider>
    );

    expect(screen.getByText('执行失败')).toBeInTheDocument();
    expect(screen.getByRole('alert')).toHaveTextContent('评测工作区创建失败。');
  });
});
