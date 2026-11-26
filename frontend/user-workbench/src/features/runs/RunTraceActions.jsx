import { useState } from 'react';
import {
  Alert,
  Box,
  Button,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  Divider,
  Stack,
  Typography
} from '@mui/material';
import { useMutation, useQuery } from '@tanstack/react-query';
import { traceExportUrl, runsApiExtras } from '../../api/traceExport';
import { useI18n } from '../../i18n/I18nProvider';

/**
 * 运行的开放出口：导出标准 Trace，或把这次运行脱敏沉淀成评测用例。
 *
 * <p>导出走浏览器原生下载，服务端只读不写；沉淀必须先看预览——预览里的内容就是将要写进数据集的
 * 全部内容，不会再多带一份原始回复或文件内容。
 */
export default function RunTraceActions({ runId, onOpenEvalLab }) {
  const { t } = useI18n();
  const [previewOpen, setPreviewOpen] = useState(false);
  const [exported, setExported] = useState(null);
  const [error, setError] = useState('');

  const preview = useQuery({
    queryKey: ['eval-case-preview', runId],
    queryFn: () => runsApiExtras.evalCasePreview(runId),
    enabled: previewOpen && Boolean(runId)
  });

  const exportCase = useMutation({
    mutationFn: () => runsApiExtras.exportEvalCase(runId),
    onSuccess: result => {
      setExported(result);
      setError('');
    },
    onError: failure => setError(failure.message)
  });

  return (
    <Box sx={{ px: 2, py: 0.75, borderBottom: '1px solid', borderColor: 'divider' }}>
      <Stack direction="row" spacing={0.75} sx={{ alignItems: 'center', flexWrap: 'wrap' }}>
        <Typography variant="caption" color="text.secondary">
          {t('traceOpen.title')}
        </Typography>
        <Button
          size="small"
          variant="outlined"
          data-testid="export-otel"
          onClick={() => window.open(traceExportUrl(runId, 'otel'), '_blank', 'noopener')}
        >
          {t('traceOpen.otel')}
        </Button>
        <Button
          size="small"
          variant="outlined"
          data-testid="export-glassbox"
          onClick={() => window.open(traceExportUrl(runId, 'glassbox'), '_blank', 'noopener')}
        >
          {t('traceOpen.glassbox')}
        </Button>
        <Button
          size="small"
          variant="contained"
          data-testid="distill-eval-case"
          onClick={() => {
            setExported(null);
            setError('');
            setPreviewOpen(true);
          }}
        >
          {t('traceOpen.distill')}
        </Button>
        {exported && (
          <Typography variant="caption" color="success.main">
            {t('traceOpen.exported', { code: exported.datasetCode })}
          </Typography>
        )}
      </Stack>

      <Dialog open={previewOpen} onClose={() => setPreviewOpen(false)} maxWidth="md" fullWidth>
        <DialogTitle>{t('traceOpen.dialogTitle')}</DialogTitle>
        <DialogContent>
          <Stack spacing={1.25} sx={{ mt: 0.5 }}>
            <Alert severity="info">{t('traceOpen.dialogHint')}</Alert>
            {preview.error && <Alert severity="error">{preview.error.message}</Alert>}
            {preview.data && (
              <>
                <Typography variant="subtitle2">{preview.data.titleZh}</Typography>
                <Typography variant="caption" color="text.secondary">
                  {preview.data.datasetCode} · {preview.data.sampleKey}
                </Typography>
                <Divider />
                <Typography variant="caption" color="text.secondary">
                  {t('traceOpen.prompt')}
                </Typography>
                <Typography variant="body2" sx={{ whiteSpace: 'pre-wrap' }}>
                  {preview.data.prompt}
                </Typography>
                <Typography variant="caption" color="text.secondary">
                  {t('traceOpen.assertions')}
                </Typography>
                {preview.data.assertions.map(item => (
                  <Typography key={`${item.type}:${item.expected}`} variant="body2">
                    · {item.type} = {String(item.expected)}
                  </Typography>
                ))}
                {(preview.data.limitations ?? []).map(item => (
                  <Typography key={item} variant="caption" color="text.secondary">
                    · {item}
                  </Typography>
                ))}
              </>
            )}
            {error && <Alert severity="error">{error}</Alert>}
            {exported && (
              <Alert
                severity="success"
                action={
                  <Button size="small" onClick={() => onOpenEvalLab?.(exported.datasetId)}>
                    {t('traceOpen.openEvalLab')}
                  </Button>
                }
              >
                {t('traceOpen.exportedHint', { code: exported.datasetCode })}
              </Alert>
            )}
          </Stack>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setPreviewOpen(false)}>{t('common.cancel')}</Button>
          <Button
            variant="contained"
            data-testid="distill-eval-confirm"
            loading={exportCase.isPending}
            onClick={() => exportCase.mutate()}
          >
            {t('traceOpen.distillConfirm')}
          </Button>
        </DialogActions>
      </Dialog>
    </Box>
  );
}
