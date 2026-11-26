import { Suspense, useState } from 'react';
import { Box, Drawer } from '@mui/material';
import { Outlet, useLocation } from 'react-router-dom';
import Sidebar from './Sidebar';
import TopBar from './TopBar';
import RouteFallback from './RouteFallback';
import ErrorBoundary from '../components/ErrorBoundary';
import { PageChromeProvider } from './pageChrome';
import { googleTokens } from '../theme/googleTheme';

/**
 * 极简双栏应用壳：左侧 240px 导航 + 56px 扁平顶栏。
 *
 * <p>路由内容外面套了「错误边界 → Suspense」：单个页面渲染失败或懒加载 chunk 报错时，
 * 只有主区域降级，导航与顶栏仍然可用；边界以 `location.key` 复位，切换即可重试。
 */
export default function WorkbenchShell() {
  const [navOpen, setNavOpen] = useState(false);
  const location = useLocation();

  return (
    <PageChromeProvider>
      <Box
        sx={{
          height: '100dvh',
          display: 'flex',
          overflow: 'hidden',
          bgcolor: googleTokens.canvas
        }}
      >
        <Box
          component="aside"
          sx={{
            width: { md: 200, lg: 240 },
            flexShrink: 0,
            display: { xs: 'none', md: 'block' },
            bgcolor: googleTokens.sidebar
          }}
        >
          <Sidebar />
        </Box>

        <Box sx={{ flex: 1, minWidth: 0, display: 'flex', flexDirection: 'column' }}>
          <TopBar onOpenNav={() => setNavOpen(true)} />
          <Box
            component="main"
            id="workspace"
            sx={{ flex: 1, minHeight: 0, overflow: 'auto', position: 'relative' }}
          >
            <ErrorBoundary scope="global" resetKeys={[location.key]}>
              <Suspense fallback={<RouteFallback />}>
                <Outlet />
              </Suspense>
            </ErrorBoundary>
          </Box>
        </Box>

        <Drawer
          open={navOpen}
          onClose={() => setNavOpen(false)}
          slotProps={{
            paper: { sx: { width: 260, bgcolor: googleTokens.sidebar, backgroundImage: 'none' } }
          }}
        >
          <Sidebar onNavigate={() => setNavOpen(false)} />
        </Drawer>
      </Box>
    </PageChromeProvider>
  );
}
