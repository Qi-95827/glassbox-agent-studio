import { lazy } from 'react';
import { HashRouter, Navigate, Route, Routes } from 'react-router-dom';
import { Box, Button, Typography } from '@mui/material';
import WorkbenchShell from './layout/WorkbenchShell';
import AuthGate from './features/auth/AuthGate';
import { ToastProvider } from './components/ToastProvider';
import { useI18n } from './i18n/I18nProvider';

/**
 * 路由页全部懒加载。
 *
 * <p>首屏只需要应用壳与首页；`ArchitecturePage` 单文件超过 4,300 行，同步引入会让所有
 * 用户在任何页面都先下载并解析一遍架构图谱。拆成独立 chunk 后，只有真正进入对应路由才拉取。
 * 加载占位与错误恢复由 `WorkbenchShell` 内的 Suspense / ErrorBoundary 统一提供。
 */
const HomePage = lazy(() => import('./features/tasks/HomePage'));
const RunHistoryPage = lazy(() => import('./features/runs/RunHistoryPage'));
const RunDetailPage = lazy(() => import('./features/runs/RunDetailPage'));
const AgentsPage = lazy(() => import('./features/agents/AgentsPage'));
const WorkspacesPage = lazy(() => import('./features/workspaces/WorkspacesPage'));
const EvaluationsPage = lazy(() => import('./features/evaluation/EvalLabPage'));
const MonitoringPage = lazy(() => import('./features/monitoring/MonitoringPage'));
const ArchitecturePage = lazy(() => import('./features/architecture/ArchitecturePage'));
const SettingsPage = lazy(() => import('./features/settings/SettingsPage'));
const DocsPage = lazy(() => import('./features/docs/DocsPage'));

function NotFound() {
  const { t } = useI18n();
  return (
    <Box sx={{ p: 6, textAlign: 'center' }}>
      <Typography sx={{ fontSize: 22, fontWeight: 500 }}>{t('app.notFoundTitle')}</Typography>
      <Typography variant="body2" color="text.secondary" sx={{ mt: 1 }}>
        {t('app.notFoundDescription')}
      </Typography>
      <Button href="#/" variant="contained" sx={{ mt: 2 }}>
        {t('app.backToWorkbench')}
      </Button>
    </Box>
  );
}

/**
 * 使用 HashRouter：后端未配置 SPA fallback 时，刷新任意深链也不会 404。
 * 路由只负责页面选择，运行与任务状态仍由服务端 API 决定。
 */
export default function App() {
  return (
    <HashRouter>
      <AuthGate>
        <ToastProvider>
          <Routes>
            <Route element={<WorkbenchShell />}>
              <Route index element={<HomePage />} />
              <Route path="runs" element={<RunHistoryPage />} />
              <Route path="runs/:runId" element={<RunDetailPage />} />
              <Route path="agents" element={<AgentsPage />} />
              <Route path="workspaces" element={<WorkspacesPage />} />
              <Route path="evaluations" element={<EvaluationsPage />} />
              <Route path="monitoring" element={<MonitoringPage />} />
              <Route path="architecture" element={<ArchitecturePage />} />
              <Route path="settings" element={<SettingsPage />} />
              <Route path="docs" element={<DocsPage />} />
              <Route path="workspace" element={<Navigate to="/" replace />} />
              <Route path="*" element={<NotFound />} />
            </Route>
          </Routes>
        </ToastProvider>
      </AuthGate>
    </HashRouter>
  );
}
