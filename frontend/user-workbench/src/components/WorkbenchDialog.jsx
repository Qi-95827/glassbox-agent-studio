import { useState } from 'react';
import {
  Accordion,
  AccordionDetails,
  AccordionSummary,
  Alert,
  Button,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  IconButton,
  Stack,
  Typography,
  useMediaQuery
} from '@mui/material';
import CloseRounded from '@mui/icons-material/CloseRounded';
import ExpandMoreRounded from '@mui/icons-material/ExpandMoreRounded';
import { googleTokens } from '../theme/googleTheme';

/** 统一弹窗：24px 圆角、无重阴影、顶部标题与关闭按钮。 */
export function WorkbenchDialog({
  title,
  subtitle,
  open,
  onCancel,
  onOk,
  footer,
  width = 640,
  okText = '确认',
  cancelText = '取消',
  confirmLoading,
  children
}) {
  const fullScreen = useMediaQuery('(max-width:599px)');
  return (
    <Dialog
      open={open}
      onClose={onCancel}
      fullScreen={fullScreen}
      fullWidth
      maxWidth={false}
      aria-labelledby="workbench-dialog-title"
      slotProps={{ paper: { sx: { maxWidth: fullScreen ? '100%' : width } } }}
    >
      <DialogTitle
        id="workbench-dialog-title"
        sx={{ display: 'flex', alignItems: 'flex-start', gap: 2, pb: 0.5 }}
      >
        <Stack sx={{ minWidth: 0, flex: 1 }}>
          <Typography component="span" sx={{ fontSize: 20, fontWeight: 500, lineHeight: 1.4 }}>
            {title}
          </Typography>
          {subtitle && (
            <Typography variant="body2" color="text.secondary" sx={{ mt: 0.4 }}>
              {subtitle}
            </Typography>
          )}
        </Stack>
        <IconButton onClick={onCancel} aria-label="关闭" size="small">
          <CloseRounded fontSize="small" />
        </IconButton>
      </DialogTitle>
      <DialogContent sx={{ overflowWrap: 'anywhere' }}>{children}</DialogContent>
      {footer !== null && footer}
      {footer === undefined && onOk && (
        <DialogActions sx={{ px: 2.5, py: 1.8 }}>
          <Button onClick={onCancel} color="inherit">
            {cancelText}
          </Button>
          <Button variant="contained" onClick={onOk} loading={confirmLoading}>
            {okText}
          </Button>
        </DialogActions>
      )}
    </Dialog>
  );
}

/** 折叠分区：标题 + 可选摘要，内容跟随 M3 描边卡片。 */
export function TraceSection({
  title,
  subtitle,
  children,
  defaultExpanded = false,
  dense = false
}) {
  const [expanded, setExpanded] = useState(defaultExpanded);
  return (
    <Accordion
      expanded={expanded}
      onChange={(_, open) => setExpanded(open)}
      sx={{ border: 'none', bgcolor: 'transparent', '&:before': { display: 'none' } }}
    >
      <AccordionSummary
        expandIcon={<ExpandMoreRounded fontSize="small" />}
        sx={{ px: dense ? 0 : 1, minHeight: 40, '& .MuiAccordionSummary-content': { my: 1 } }}
      >
        <Stack sx={{ minWidth: 0 }}>
          <Typography variant="body2" sx={{ fontWeight: 600 }}>
            {title}
          </Typography>
          {subtitle && (
            <Typography variant="caption" color="text.secondary">
              {subtitle}
            </Typography>
          )}
        </Stack>
      </AccordionSummary>
      <AccordionDetails sx={{ px: dense ? 0 : 1, pt: 0 }}>{children}</AccordionDetails>
    </Accordion>
  );
}

/** 只读代码/证据块。 */
export function Readout({ children, mono = false, maxHeight = 260 }) {
  return (
    <Typography
      component="pre"
      variant="body2"
      sx={{
        m: 0,
        bgcolor: googleTokens.canvas,
        border: `1px solid ${googleTokens.outline}`,
        px: 1.5,
        py: 1.2,
        borderRadius: 2,
        whiteSpace: 'pre-wrap',
        overflowWrap: 'anywhere',
        overflowY: 'auto',
        maxHeight,
        fontSize: 12.5,
        fontFamily: mono ? googleTokens.mono : 'inherit'
      }}
    >
      {children}
    </Typography>
  );
}

/** 统一的错误提示条，避免每页重复样式。 */
export function ErrorAlert({ error, onRetry }) {
  if (!error) return null;
  return (
    <Alert
      severity="error"
      action={
        onRetry ? (
          <Button color="inherit" size="small" onClick={onRetry}>
            重试
          </Button>
        ) : undefined
      }
    >
      {error.message || String(error)}
    </Alert>
  );
}

export default WorkbenchDialog;
