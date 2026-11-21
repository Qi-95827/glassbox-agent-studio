import { createRoot } from 'react-dom/client';
import { CssBaseline, ThemeProvider } from '@mui/material';
import { QueryClientProvider } from '@tanstack/react-query';
import App from './App';
import theme from './theme/googleTheme';
import { queryClient } from './app/queryClient';
import './styles/global.css';

/** 纯入口：主题、缓存、路由与应用壳的装配，不承载业务状态。 */
createRoot(document.getElementById('root')).render(
  <ThemeProvider theme={theme}>
    <CssBaseline />
    <QueryClientProvider client={queryClient}>
      <App />
    </QueryClientProvider>
  </ThemeProvider>
);
