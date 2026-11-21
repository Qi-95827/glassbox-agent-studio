import { useState } from 'react';
import { Box, Drawer } from '@mui/material';
import { Outlet } from 'react-router-dom';
import Sidebar from './Sidebar';
import TopBar from './TopBar';
import { PageChromeProvider } from './pageChrome';
import { googleTokens } from '../theme/googleTheme';

/** 极简双栏应用壳：左侧 240px 导航 + 56px 扁平顶栏。 */
export default function WorkbenchShell() {
  const [navOpen, setNavOpen] = useState(false);

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
            <Outlet />
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
