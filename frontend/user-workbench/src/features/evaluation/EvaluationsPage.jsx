import { useState } from 'react';
import { Box, Tab, Tabs, Typography } from '@mui/material';
import { useNavigate } from 'react-router-dom';
import { usePageChrome } from '../../layout/pageChrome';
import EvaluationBatchesPanel from './EvaluationBatchesPanel';
import DatasetManager from './DatasetManager';
import MonitoringPanel from './MonitoringPanel';
import { googleTokens } from '../../theme/googleTheme';
import { useI18n } from '../../i18n/I18nProvider';

/** 评测与监控：种子批次、可管理数据集、运维告警。 */
export default function EvaluationsPage() {
  const { t } = useI18n();
  const navigate = useNavigate();
  const [tab, setTab] = useState('batches');
  usePageChrome({ trail: [{ label: t('nav.workspace') }, { label: t('nav.evaluations') }] });

  return (
    <Box sx={{ overflowY: 'auto', height: '100%' }}>
      <Box sx={{ maxWidth: 1180, mx: 'auto', px: { xs: 2, sm: 3 }, py: { xs: 3, md: 4 } }}>
        <Box sx={{ mb: 2 }}>
          <Typography component="h1" sx={{ fontSize: 22, fontWeight: 500 }}>
            {t('evaluation.title')}
          </Typography>
          <Typography variant="body2" color="text.secondary">
            {t('evaluation.description')}
          </Typography>
        </Box>

        <Tabs
          value={tab}
          onChange={(_, value) => setTab(value)}
          sx={{ borderBottom: `1px solid ${googleTokens.outline}`, mb: 2.5 }}
        >
          <Tab value="batches" label={t('evaluation.tab.batches')} />
          <Tab value="datasets" label={t('evaluation.tab.datasets')} />
          <Tab value="monitoring" label={t('evaluation.tab.monitoring')} />
        </Tabs>

        {tab === 'batches' && (
          <EvaluationBatchesPanel onOpenRun={runId => navigate(`/runs/${runId}`)} />
        )}
        {tab === 'datasets' && <DatasetManager onOpenRun={runId => navigate(`/runs/${runId}`)} />}
        {tab === 'monitoring' && <MonitoringPanel />}
      </Box>
    </Box>
  );
}
