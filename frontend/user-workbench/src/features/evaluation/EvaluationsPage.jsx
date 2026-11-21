import { useState } from 'react';
import { Box, Tab, Tabs, Typography } from '@mui/material';
import { useNavigate } from 'react-router-dom';
import { usePageChrome } from '../../layout/pageChrome';
import EvaluationBatchesPanel from './EvaluationBatchesPanel';
import DatasetManager from './DatasetManager';
import MonitoringPanel from './MonitoringPanel';
import { googleTokens } from '../../theme/googleTheme';

/** 评测与监控：种子批次、可管理数据集、运维告警。 */
export default function EvaluationsPage() {
  const navigate = useNavigate();
  const [tab, setTab] = useState('batches');
  usePageChrome({ trail: [{ label: '工作空间' }, { label: '评测与监控' }] });

  return (
    <Box sx={{ overflowY: 'auto', height: '100%' }}>
      <Box sx={{ maxWidth: 1180, mx: 'auto', px: { xs: 2, sm: 3 }, py: { xs: 3, md: 4 } }}>
        <Box sx={{ mb: 2 }}>
          <Typography component="h1" sx={{ fontSize: 22, fontWeight: 500 }}>
            评测与监控
          </Typography>
          <Typography variant="body2" color="text.secondary">
            可重复的本地种子评测、不可变数据集版本与本机运行记录；不代表生产环境结论。
          </Typography>
        </Box>

        <Tabs
          value={tab}
          onChange={(_, value) => setTab(value)}
          sx={{ borderBottom: `1px solid ${googleTokens.outline}`, mb: 2.5 }}
        >
          <Tab value="batches" label="评测批次" />
          <Tab value="datasets" label="数据集" />
          <Tab value="monitoring" label="监控与告警" />
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
