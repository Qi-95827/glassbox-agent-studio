import { Box, Stack, Typography } from '@mui/material';
import { usePageChrome } from '../../layout/pageChrome';
import MonitoringPanel from '../evaluation/MonitoringPanel';
import ArchitectureLensButton from '../architecture/ArchitectureLensButton';
import { useI18n } from '../../i18n/I18nProvider';

/**
 * 系统监控独立一级工作区。
 *
 * <p>只承接生产/日常运行健康度：KPI、趋势、分布、运行明细、5 步根因排查与系统告警；
 * 与评测实验室的能力考试完全分离，避免把“能力分数”和“运维健康”混为一谈。
 */
export default function MonitoringPage() {
  const { t } = useI18n();
  usePageChrome({ trail: [{ label: t('nav.workspace') }, { label: t('nav.monitoring') }] });

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
              {t('monitoringPage.title')}
            </Typography>
            <Typography variant="body2" color="text.secondary" sx={{ maxWidth: 860 }}>
              {t('monitoringPage.description')}
            </Typography>
          </Box>
          <ArchitectureLensButton topic="delivery-monitoring" />
        </Stack>
        <MonitoringPanel />
      </Box>
    </Box>
  );
}
