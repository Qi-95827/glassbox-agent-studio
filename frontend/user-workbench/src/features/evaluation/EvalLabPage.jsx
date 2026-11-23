import { useState } from 'react';
import {
  Box,
  Dialog,
  DialogContent,
  DialogTitle,
  Divider,
  Stack,
  Tab,
  Tabs,
  Typography
} from '@mui/material';
import { usePageChrome } from '../../layout/pageChrome';
import { googleTokens } from '../../theme/googleTheme';
import { useI18n } from '../../i18n/I18nProvider';
import { useEvalLabMeta } from '../../hooks/useEvalLab';
import ArchitectureLensButton from '../architecture/ArchitectureLensButton';
import EvaluationBatchesPanel from './EvaluationBatchesPanel';
import DatasetManager from './DatasetManager';
import EvalRunsPanel from './EvalRunsPanel';
import BenchmarkHubPanel from './BenchmarkHubPanel';

function CasePeek({ item, onClose }) {
  const { t, locale } = useI18n();
  return (
    <Dialog open={Boolean(item)} onClose={onClose} maxWidth="md" fullWidth>
      <DialogTitle>{item ? (locale === 'en-US' ? item.titleEn : item.titleZh) : ''}</DialogTitle>
      <DialogContent dividers>
        {item && (
          <Stack spacing={2}>
            <Typography variant="caption" color="text.secondary">
              {item.category} · {item.sampleId}
            </Typography>
            <Box>
              <Typography sx={{ fontSize: 13, fontWeight: 600, mb: 0.5 }}>
                {t('evalLab.case.metrics')}
              </Typography>
              <Box
                component="pre"
                sx={{
                  m: 0,
                  p: 1.5,
                  bgcolor: '#F8F9FA',
                  borderRadius: 2,
                  fontSize: 12,
                  overflowX: 'auto'
                }}
              >
                {JSON.stringify(item.metrics, null, 2)}
              </Box>
            </Box>
            <Box>
              <Typography sx={{ fontSize: 13, fontWeight: 600, mb: 0.5 }}>
                {t('evalLab.case.assertions')}
              </Typography>
              <Box
                component="pre"
                sx={{
                  m: 0,
                  p: 1.5,
                  bgcolor: '#F8F9FA',
                  borderRadius: 2,
                  fontSize: 12,
                  overflowX: 'auto'
                }}
              >
                {JSON.stringify(item.assertions, null, 2)}
              </Box>
            </Box>
            {item.rawOutput && (
              <Box>
                <Typography sx={{ fontSize: 13, fontWeight: 600, mb: 0.5 }}>
                  {t('evalLab.case.rawOutput')}
                </Typography>
                <Box
                  component="pre"
                  sx={{
                    m: 0,
                    p: 1.5,
                    bgcolor: '#F8F9FA',
                    borderRadius: 2,
                    fontSize: 12,
                    overflowX: 'auto'
                  }}
                >
                  {item.rawOutput}
                </Box>
              </Box>
            )}
          </Stack>
        )}
      </DialogContent>
    </Dialog>
  );
}

/** 评测实验室：六类真实评测、数据集管理与经典底座回归。 */
export default function EvalLabPage() {
  const { t } = useI18n();
  const meta = useEvalLabMeta();
  const [tab, setTab] = useState('runs');
  const [peek, setPeek] = useState(null);
  const [seed, setSeed] = useState(null);
  usePageChrome({ trail: [{ label: t('nav.workspace') }, { label: t('nav.evaluations') }] });

  const runDataset = (source, key) => {
    setSeed({ source, key, nonce: Date.now() });
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
          onChange={(_, value) => setTab(value)}
          sx={{ borderBottom: `1px solid ${googleTokens.outline}`, mb: 2.5 }}
        >
          <Tab value="runs" label={t('evalLab.tab.runs')} />
          <Tab value="hub" label={t('evalLab.tab.hub')} />
          <Tab value="legacy" label={t('evalLab.tab.legacy')} />
        </Tabs>

        {tab === 'runs' && <EvalRunsPanel meta={meta.data} onOpenCase={setPeek} seed={seed} />}
        {tab === 'hub' && <BenchmarkHubPanel meta={meta.data} onRun={runDataset} />}
        {tab === 'legacy' && (
          <Stack spacing={3}>
            <EvaluationBatchesPanel />
            <Divider />
            <DatasetManager />
          </Stack>
        )}
      </Box>
      <CasePeek item={peek} onClose={() => setPeek(null)} />
    </Box>
  );
}
