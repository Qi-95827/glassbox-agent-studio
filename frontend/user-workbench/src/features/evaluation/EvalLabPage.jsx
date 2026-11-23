import { useCallback, useMemo, useState } from 'react';
import { Box, Divider, Stack, Tab, Tabs, Typography } from '@mui/material';
import { useNavigate } from 'react-router-dom';
import { usePageChrome } from '../../layout/pageChrome';
import { googleTokens } from '../../theme/googleTheme';
import { useI18n } from '../../i18n/I18nProvider';
import { useEvalLabMeta } from '../../hooks/useEvalLab';
import ArchitectureLensButton from '../architecture/ArchitectureLensButton';
import EvaluationBatchesPanel from './EvaluationBatchesPanel';
import DatasetManager from './DatasetManager';
import EvalRunsPanel from './EvalRunsPanel';
import BenchmarkHubPanel from './BenchmarkHubPanel';
import EvalAbComparePanel from './EvalAbComparePanel';
import EvalCaseDetailDrawer from './EvalCaseDetailDrawer';

/** 评测实验室：六类真实评测、Benchmark Hub、A/B 消融与经典底座回归。 */
export default function EvalLabPage() {
  const { t } = useI18n();
  const navigate = useNavigate();
  const meta = useEvalLabMeta();
  const [tab, setTab] = useState('runs');
  const [selectedCase, setSelectedCase] = useState(null);
  const [initialLaunchPack, setInitialLaunchPack] = useState(null);
  const consumeLaunch = useCallback(() => setInitialLaunchPack(null), []);
  usePageChrome({ trail: [{ label: t('nav.workspace') }, { label: t('nav.evaluations') }] });

  const categoryLabel = useMemo(
    () =>
      Object.fromEntries(
        (meta.data?.categories ?? []).map(category => [
          category.code,
          `${category.labelZh} / ${category.labelEn}`
        ])
      ),
    [meta.data]
  );

  const runDataset = (source, key) => {
    setInitialLaunchPack({ source, key });
    setTab('runs');
  };

  return (
    <Box sx={{ overflowY: 'auto', height: '100%' }}>
      <Box sx={{ maxWidth: 1240, mx: 'auto', px: { xs: 2, sm: 3 }, py: { xs: 3, md: 4 } }}>
        <Stack
          direction={{ xs: 'column', sm: 'row' }}
          spacing={1.5}
          sx={{ mb: 2, justifyContent: 'space-between', alignItems: { sm: 'flex-start' } }}
        >
          <Box>
            <Typography component="h1" sx={{ fontSize: 22, fontWeight: 500 }}>
              {t('evalLab.title')}
            </Typography>
            <Typography variant="body2" color="text.secondary" sx={{ maxWidth: 860 }}>
              {t('evalLab.description')}
            </Typography>
          </Box>
          <ArchitectureLensButton topic="delivery-monitoring" />
        </Stack>

        <Tabs
          value={tab}
          variant="scrollable"
          scrollButtons="auto"
          allowScrollButtonsMobile
          onChange={(_, value) => setTab(value)}
          sx={{ borderBottom: `1px solid ${googleTokens.outline}`, mb: 2.5 }}
        >
          <Tab value="runs" label={t('evalLab.tab.runs')} />
          <Tab value="hub" label={t('evalLab.tab.hub')} />
          <Tab value="compare" label={t('evalLab.tab.compare')} />
          <Tab value="legacy" label={t('evalLab.tab.legacy')} />
        </Tabs>

        {tab === 'runs' && (
          <EvalRunsPanel
            meta={meta.data}
            onOpenCase={setSelectedCase}
            initialLaunchPack={initialLaunchPack}
            onLaunchConsumed={consumeLaunch}
          />
        )}
        {tab === 'hub' && <BenchmarkHubPanel meta={meta.data} onRun={runDataset} />}
        {tab === 'compare' && <EvalAbComparePanel meta={meta.data} />}
        {tab === 'legacy' && (
          <Stack spacing={3}>
            <EvaluationBatchesPanel />
            <Divider />
            <DatasetManager />
          </Stack>
        )}
      </Box>
      <EvalCaseDetailDrawer
        open={Boolean(selectedCase)}
        item={selectedCase}
        categoryLabel={categoryLabel}
        onClose={() => setSelectedCase(null)}
        onOpenRun={runId => navigate(`/runs/${runId}`)}
      />
    </Box>
  );
}
