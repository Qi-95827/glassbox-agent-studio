import { Component } from 'react';
import { Box, Button, Stack, Typography } from '@mui/material';
import RefreshRounded from '@mui/icons-material/RefreshRounded';
import { useI18n } from '../i18n/I18nProvider';
import { googleTokens } from '../theme/googleTheme';

/**
 * 局部错误边界：单个可视化组件抛错时只降级该区域，不让整页白屏。
 *
 * <p>选择类组件而不是函数组件，是因为 React 只把 `getDerivedStateFromError` /
 * `componentDidCatch` 暴露给类组件；文案与主题仍由外层函数组件注入，保持 i18n 一致。
 *
 * <p>`resetKeys` 变化时自动复位：切换运行、切换节点后不应停留在上一次的错误界面。
 * 复位只清空边界自身的错误状态，不伪造被包裹组件的成功状态——如果故障仍在，边界会再次捕获。
 */
class ErrorBoundaryInner extends Component {
  constructor(props) {
    super(props);
    this.state = { error: null };
    this.reset = this.reset.bind(this);
  }

  static getDerivedStateFromError(error) {
    return { error };
  }

  componentDidCatch(error, info) {
    // 上报给调用方（例如控制台或未来的前端错误采集）；边界自身的降级不依赖这里。
    this.props.onError?.(error, info);
  }

  componentDidUpdate(previous) {
    if (!this.state.error) return;
    if (previous.resetToken !== this.props.resetToken) this.reset();
  }

  reset() {
    this.setState({ error: null });
    this.props.onReset?.();
  }

  render() {
    const { error } = this.state;
    if (!error) return this.props.children;
    return this.props.renderFallback(error, this.reset);
  }
}

/**
 * 错误边界。
 *
 * @param children 受保护的子树。
 * @param scope `global` 全站兜底、`canvas` 画布局部、`drawer` 抽屉/面板局部，只影响外观与文案。
 * @param resetKeys 任一值变化时自动复位；用于「切换运行/节点后重新尝试渲染」。
 * @param onReset 复位后的回调。
 * @param onError 捕获时的回调，参数为原始 error 与 React 组件栈。
 */
export default function ErrorBoundary({
  children,
  scope = 'global',
  resetKeys = [],
  onReset,
  onError
}) {
  const { t } = useI18n();
  const resetToken = resetKeys.map(value => String(value ?? '')).join('\u0000');
  const compact = scope !== 'global';

  const renderFallback = (error, reset) => (
    <Box
      role="alert"
      data-testid="error-boundary-fallback"
      data-scope={scope}
      sx={{
        display: 'flex',
        flexDirection: 'column',
        alignItems: compact ? 'stretch' : 'center',
        justifyContent: compact ? 'flex-start' : 'center',
        gap: 1,
        m: compact ? 0 : 4,
        p: compact ? 1.5 : 3,
        minHeight: compact ? 120 : 240,
        border: `1px solid #F2B8B5`,
        borderRadius: 3,
        bgcolor: '#FEF7F7',
        textAlign: compact ? 'left' : 'center'
      }}
    >
      <Typography sx={{ fontSize: compact ? 13.5 : 16, fontWeight: 600, color: '#C5221F' }}>
        {t('errorBoundary.title')}
      </Typography>
      <Typography variant="body2" sx={{ color: googleTokens.textSecondary }}>
        {scope === 'global' ? t('errorBoundary.globalDescription') : t('errorBoundary.description')}
      </Typography>
      <Typography
        component="pre"
        sx={{
          m: 0,
          maxHeight: 120,
          maxWidth: '100%',
          overflow: 'auto',
          fontSize: 11.5,
          fontFamily: googleTokens.mono,
          color: '#8C1D18',
          bgcolor: '#FFFFFF',
          border: '1px solid #F6DEDC',
          borderRadius: 2,
          p: 1
        }}
      >
        {String(error?.message || error || '')}
      </Typography>
      <Stack direction="row" spacing={1} sx={{ justifyContent: compact ? 'flex-start' : 'center' }}>
        <Button size="small" variant="outlined" startIcon={<RefreshRounded />} onClick={reset}>
          {t('common.retry')}
        </Button>
      </Stack>
    </Box>
  );

  return (
    <ErrorBoundaryInner
      resetToken={resetToken}
      onReset={onReset}
      onError={onError}
      renderFallback={renderFallback}
    >
      {children}
    </ErrorBoundaryInner>
  );
}
