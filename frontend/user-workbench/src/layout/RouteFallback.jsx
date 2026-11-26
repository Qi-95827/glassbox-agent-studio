import { Box, CircularProgress, Stack, Typography } from '@mui/material';
import { useI18n } from '../i18n/I18nProvider';

/**
 * 路由级加载占位。
 *
 * <p>懒加载页面下载完成前保留与页面一致的垂直留白，避免内容出现时整页跳动；
 * `aria-busy` 让读屏用户知道这里正在读取而不是空页面。
 */
export default function RouteFallback() {
  const { t } = useI18n();
  return (
    <Box
      sx={{ py: 8, display: 'grid', placeItems: 'center', minHeight: 240 }}
      aria-busy="true"
      data-testid="route-fallback"
    >
      <Stack spacing={1.2} sx={{ alignItems: 'center' }}>
        <CircularProgress size={22} />
        <Typography variant="body2" color="text.secondary">
          {t('common.loading')}
        </Typography>
      </Stack>
    </Box>
  );
}
