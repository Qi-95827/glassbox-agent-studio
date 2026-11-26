import { createRoot } from 'react-dom/client';
import { CssBaseline, ThemeProvider } from '@mui/material';
import { QueryClientProvider } from '@tanstack/react-query';
import App from './App';
import ErrorBoundary from './components/ErrorBoundary';
import theme from './theme/googleTheme';
import { queryClient } from './app/queryClient';
import { I18nProvider } from './i18n/I18nProvider';
import './styles/global.css';

/**
 * 纯入口：主题、缓存、国际化、路由与应用壳的装配，不承载业务状态。
 *
 * <p>最外层错误边界位于 I18nProvider 之内，保证降级文案仍按当前语言渲染；
 * 它兜住应用壳自身的异常，页面级异常由 `WorkbenchShell` 内的边界就近处理。
 */
createRoot(document.getElementById('root')).render(
  <ThemeProvider theme={theme}>
    <CssBaseline />
    <QueryClientProvider client={queryClient}>
      <I18nProvider>
        <ErrorBoundary scope="global">
          <App />
        </ErrorBoundary>
      </I18nProvider>
    </QueryClientProvider>
  </ThemeProvider>
);
