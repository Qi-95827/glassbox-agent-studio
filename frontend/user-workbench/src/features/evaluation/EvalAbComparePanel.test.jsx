import { expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { I18nProvider } from '../../i18n/I18nProvider';
import { ToastProvider } from '../../components/ToastProvider';
import EvalAbComparePanel from './EvalAbComparePanel';
import EvalRunsPanel from './EvalRunsPanel';

const { runs, row, setComparable } = vi.hoisted(() => {
  let comparable = true;
  return {
    runs: ['baseline', 'candidate'].map(id => ({
      id,
      status: 'COMPLETED',
      sourceKind: 'HUB',
      config: { datasetName: 'LongBench v2', datasetHash: 'same' },
      categories: ['CONTEXT_COMPACTION'],
      summary: {
        overall: { total: 1, passed: 1, passRate: 1, score: 100, totalCostUsd: 0.001 },
        categories: {
          CONTEXT_COMPACTION: { total: 1, passed: 1, passRate: 1, score: 100, passHatK: 1 }
        }
      }
    })),
    row: {
      key: 'q1',
      id: 'case-one',
      category: 'CONTEXT_COMPACTION',
      sampleId: 'q1',
      titleZh: '中文题目',
      titleEn: 'English question',
      baselineScore: 100,
      candidateScore: 100,
      passed: true,
      repeatIndex: 1,
      codeScore: 1,
      status: 'UNCHANGED'
    },
    setComparable: value => (value == null ? comparable : (comparable = value))
  };
});

const meta = {
  categories: [
    ['CODING_REFLECTION', 'Coding'],
    ['TOOL_MCP', 'Tool governance'],
    ['CONTEXT_COMPACTION', 'Compaction'],
    ['LONG_TERM_MEMORY', 'Memory'],
    ['PLANNING_MULTI_AGENT', 'Planning'],
    ['SAFETY_INJECTION', 'Safety']
  ].map(([code, labelEn]) => ({ code, labelEn, labelZh: code }))
};

vi.mock('../../hooks/useEvalLab', () => ({
  useEvalLabRuns: () => ({ data: runs }),
  useEvalLabRun: id => ({ data: { run: runs.find(run => run.id === id), cases: [row] } })
}));
vi.mock('./EvalRunDialog', () => ({ default: () => null }));
vi.mock('../../api/evalLab', () => ({
  evalLabApi: {
    compare: async () => ({
      comparable: setComparable(),
      baseline: runs[0],
      candidate: runs[1],
      categoryComparison: [
        {
          ...row,
          baselineSampleCount: 1,
          candidateSampleCount: 1,
          delta: 0
        }
      ],
      metricDeltas: setComparable()
        ? [{ metric: 'avgActualInput', baseline: 115, candidate: 31, delta: -84 }]
        : [],
      caseDiff: setComparable() ? [row] : []
    })
  }
}));

function renderPanel(panel) {
  return render(
    <QueryClientProvider client={new QueryClient()}>
      <MemoryRouter>
        <I18nProvider initialLocale="en-US">
          <ToastProvider>{panel}</ToastProvider>
        </I18nProvider>
      </MemoryRouter>
    </QueryClientProvider>
  );
}

async function openComparison() {
  renderPanel(<EvalAbComparePanel meta={meta} />);
  const selectors = screen.getAllByRole('combobox');
  await userEvent.click(selectors[0]);
  await userEvent.click(screen.getByRole('option', { name: /baseline/ }));
  await userEvent.click(selectors[1]);
  await userEvent.click(screen.getByRole('option', { name: /candidat/ }));
  await userEvent.click(screen.getByRole('button', { name: /compare/i }));
}

it('renders six radar axes and the case table on a public batch', () => {
  renderPanel(<EvalRunsPanel meta={meta} />);
  const radar = screen.getByRole('img', { name: 'Six-domain capability radar' });
  expect(radar.querySelectorAll('text')).toHaveLength(6);
  expect(radar.querySelectorAll('line[stroke-dasharray="4 4"]')).toHaveLength(5);
  expect(screen.getByText('English question')).toBeInTheDocument();
});

it('overlays both six-axis public batch series and displays real input token deltas', async () => {
  setComparable(true);
  await openComparison();
  const radar = await screen.findByRole('img', { name: 'Six-domain capability radar' });
  expect(radar.querySelectorAll('text')).toHaveLength(6);
  expect(radar.querySelectorAll('g > polygon')).toHaveLength(2);
  expect(screen.getByText('English question')).toBeInTheDocument();
  expect(screen.getByText('Mean provider-reported input tokens')).toBeInTheDocument();
});

it('keeps the radar visible and suppresses per-case deltas when public hashes differ', async () => {
  setComparable(false);
  await openComparison();
  expect(await screen.findByRole('alert')).toHaveTextContent(/question sets/i);
  expect(screen.getByRole('img', { name: 'Six-domain capability radar' })).toBeInTheDocument();
  expect(screen.queryByText('English question')).not.toBeInTheDocument();
});
