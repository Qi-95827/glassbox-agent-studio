import { HashRouter, Navigate, Route, Routes } from 'react-router-dom';
import { Box, Button, Typography } from '@mui/material';
import WorkbenchShell from './layout/WorkbenchShell';
import AuthGate from './features/auth/AuthGate';
import { ToastProvider } from './components/ToastProvider';
import HomePage from './features/tasks/HomePage';
import RunHistoryPage from './features/runs/RunHistoryPage';
import RunDetailPage from './features/runs/RunDetailPage';
import AgentsPage from './features/agents/AgentsPage';
import WorkspacesPage from './features/workspaces/WorkspacesPage';
import EvaluationsPage from './features/evaluation/EvaluationsPage';
import SettingsPage from './features/settings/SettingsPage';
import DocsPage from './features/docs/DocsPage';

function NotFound() {
  return (
    <Box sx={{ p: 6, textAlign: 'center' }}>
      <Typography sx={{ fontSize: 22, fontWeight: 500 }}>页面不存在</Typography>
      <Typography variant="body2" color="text.secondary" sx={{ mt: 1 }}>
        链接可能已失效，或当前版本尚未提供该视图。
      </Typography>
      <Button href="#/" variant="contained" sx={{ mt: 2 }}>
        返回工作台
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
