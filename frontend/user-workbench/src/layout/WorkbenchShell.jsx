import React, { useState } from 'react';
import {
  AppBar, Box, Button, Chip, Divider, Drawer, IconButton, List, ListItemButton,
  ListItemIcon, ListItemText, Stack, Toolbar, Typography
} from '@mui/material';
import { createTheme } from '@mui/material/styles';
import AccountTreeOutlined from '@mui/icons-material/AccountTreeOutlined';
import AddRounded from '@mui/icons-material/AddRounded';
import AppsOutlined from '@mui/icons-material/AppsOutlined';
import BugReportOutlined from '@mui/icons-material/BugReportOutlined';
import CodeOutlined from '@mui/icons-material/CodeOutlined';
import DashboardOutlined from '@mui/icons-material/DashboardOutlined';
import FactCheckOutlined from '@mui/icons-material/FactCheckOutlined';
import InsightsOutlined from '@mui/icons-material/InsightsOutlined';
import MenuRounded from '@mui/icons-material/MenuRounded';
import SmartToyOutlined from '@mui/icons-material/SmartToyOutlined';

export const workbenchTheme = createTheme({
  palette: {
    primary: { main: '#0b57d0', light: '#d3e3fd', dark: '#0842a0' },
    background: { default: '#f5f7fb', paper: '#ffffff' },
    text: { primary: '#182436', secondary: '#506078' },
    divider: '#dde4ee'
  },
  shape: { borderRadius: 12 },
  typography: {
    fontFamily: '"Segoe UI", "Microsoft YaHei UI", "PingFang SC", sans-serif',
    h4: { fontWeight: 650, letterSpacing: '-0.025em' },
    h5: { fontWeight: 650, letterSpacing: '-0.016em' },
    h6: { fontWeight: 650 },
    button: { textTransform: 'none', fontWeight: 600 }
  },
  components: {
    MuiButton: { defaultProps: { disableElevation: true } },
    MuiPaper: { defaultProps: { elevation: 0 } }
  }
});

const primary = [
  ['workspace', '任务工作台', AppsOutlined],
  ['overview', '运行概况', DashboardOutlined],
  ['my-agents', '我的 Agent', SmartToyOutlined]
];

export default function WorkbenchShell({ section, onSection, onNewTask, connected, syncLabel, actions, children, user, onLogout }) {
  const [drawer, setDrawer] = useState(false);
  const resources = [
    ['Agent 角色目录', SmartToyOutlined, actions.agents],
    ['代码工作区', CodeOutlined, actions.workspaces],
    ['模拟 Bug 验证', BugReportOutlined, actions.bug],
    ['评测中心', FactCheckOutlined, actions.evaluation],
    ['运行监控', InsightsOutlined, actions.monitor]
  ];

  function select(action) {
    setDrawer(false);
    action();
  }

  const navigation = <Box component="nav" aria-label="工作台导航" sx={{ p: 1.5 }}>
    <List disablePadding dense>
      {primary.map(([id, title, Icon]) => <ListItemButton key={id} selected={section === id}
        aria-current={section === id ? 'page' : undefined} onClick={() => select(() => onSection(id))}
        sx={{ borderRadius: 2, py: 1, mb: 0.5 }}>
        <ListItemIcon sx={{ minWidth: 34 }}><Icon fontSize="small" /></ListItemIcon>
        <ListItemText primary={title} />
      </ListItemButton>)}
    </List>
    <Divider sx={{ my: 1.5 }} />
    <Typography variant="overline" color="text.secondary" sx={{ px: 2 }}>资源与验证</Typography>
    <List disablePadding dense>
      {resources.map(([title, Icon, action]) => <ListItemButton key={title} onClick={() => select(action)}
        sx={{ borderRadius: 2, py: 0.9, mb: 0.25 }}>
        <ListItemIcon sx={{ minWidth: 34 }}><Icon fontSize="small" /></ListItemIcon>
        <ListItemText primary={title} />
      </ListItemButton>)}
    </List>
  </Box>;

  return <Box sx={{ minHeight: '100vh', bgcolor: 'background.default' }}>
    <AppBar color="inherit" position="sticky" sx={{ borderBottom: 1, borderColor: 'divider' }}>
      <Toolbar sx={{ gap: { xs: 0.8, sm: 2 }, minHeight: { xs: 58, sm: 64 } }}>
        <IconButton aria-label="打开导航" onClick={() => setDrawer(true)} sx={{ display: { md: 'none' } }}>
          <MenuRounded />
        </IconButton>
        <Stack direction="row" spacing={1.1} sx={{ alignItems: 'center', mr: 'auto', minWidth: 0 }}>
          <AccountTreeOutlined color="primary" sx={{ display: { xs: 'none', sm: 'block' } }} />
          <Typography variant="h6" noWrap>Agent Studio</Typography>
        </Stack>
        <Box sx={{ display: { xs: 'none', sm: 'block' } }}>
          <Chip variant="outlined" size="small" color={connected ? 'success' : 'warning'}
            label={connected ? '服务运行中' : '连接中断'} title={syncLabel} />
        </Box>
        {user && <Stack direction="row" spacing={0.5} sx={{ alignItems: 'center' }}>
          <Typography variant="caption" color="text.secondary" noWrap>{user.displayName}</Typography>
          <Button size="small" onClick={() => select(actions.myAgents)}>我的 Agent</Button>
          <Button size="small" onClick={onLogout}>退出</Button>
        </Stack>}
        <Button variant="contained" startIcon={<AddRounded />} onClick={onNewTask} sx={{ flexShrink: 0 }}>
          新建任务
        </Button>
      </Toolbar>
    </AppBar>
    {!connected && <Typography role="status" variant="body2" sx={{ bgcolor: 'warning.light', px: 2, py: 0.7 }}>
      连接中断，显示最后同步的记录。
    </Typography>}
    <Box sx={{ display: 'grid', gridTemplateColumns: { xs: 'minmax(0, 1fr)', md: '196px minmax(0, 1fr)' }, alignItems: 'start' }}>
      <Box component="aside" sx={{
        display: { xs: 'none', md: 'block' }, position: 'sticky', top: 65,
        minHeight: 'calc(100vh - 65px)', borderRight: 1, borderColor: 'divider'
      }}>{navigation}</Box>
      <Drawer open={drawer} onClose={() => setDrawer(false)}
        slotProps={{ paper: { sx: { width: 232 } } }}>
        {navigation}
      </Drawer>
      <Box component="main" id="workspace" sx={{ minWidth: 0, px: { xs: 1, sm: 2, xl: 3 }, py: { xs: 1.5, md: 2.5 } }}>
        {children}
      </Box>
    </Box>
  </Box>;
}
