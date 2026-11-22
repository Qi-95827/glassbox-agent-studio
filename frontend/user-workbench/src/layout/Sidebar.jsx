import {
  Box,
  Button,
  List,
  ListItemButton,
  ListItemIcon,
  ListItemText,
  Stack,
  Typography
} from '@mui/material';
import AddRounded from '@mui/icons-material/AddRounded';
import SpaceDashboardOutlined from '@mui/icons-material/SpaceDashboardOutlined';
import TimelineOutlined from '@mui/icons-material/TimelineOutlined';
import SmartToyOutlined from '@mui/icons-material/SmartToyOutlined';
import FolderOpenOutlined from '@mui/icons-material/FolderOpenOutlined';
import FactCheckOutlined from '@mui/icons-material/FactCheckOutlined';
import SettingsOutlined from '@mui/icons-material/SettingsOutlined';
import MenuBookOutlined from '@mui/icons-material/MenuBookOutlined';
import { useLocation, useNavigate } from 'react-router-dom';
import AppLogo from '../components/AppLogo';
import { googleTokens } from '../theme/googleTheme';
import { useI18n } from '../i18n/I18nProvider';

const navigation = [
  { key: 'nav.home', path: '/', icon: SpaceDashboardOutlined, match: value => value === '/' },
  {
    key: 'nav.runs',
    path: '/runs',
    icon: TimelineOutlined,
    match: value => value.startsWith('/runs')
  },
  {
    key: 'nav.agents',
    path: '/agents',
    icon: SmartToyOutlined,
    match: value => value.startsWith('/agents')
  },
  {
    key: 'nav.workspaces',
    path: '/workspaces',
    icon: FolderOpenOutlined,
    match: value => value.startsWith('/workspaces')
  },
  {
    key: 'nav.evaluations',
    path: '/evaluations',
    icon: FactCheckOutlined,
    match: value => value.startsWith('/evaluations')
  }
];

function NavButton({ item, active, onClick }) {
  const Icon = item.icon;
  return (
    <ListItemButton
      selected={active}
      onClick={onClick}
      aria-current={active ? 'page' : undefined}
      sx={{
        height: 40,
        px: 1.75,
        mb: 0.25,
        borderRadius: 9999,
        color: active ? googleTokens.primaryText : '#444746',
        '&.Mui-selected': { backgroundColor: googleTokens.primarySoft },
        '&.Mui-selected:hover': { backgroundColor: '#C6DAFC' },
        '&:hover': { backgroundColor: 'rgba(60,64,67,.06)' }
      }}
    >
      <ListItemIcon
        sx={{
          minWidth: 34,
          color: active ? googleTokens.primary : '#5F6368',
          '& svg': { fontSize: 20 }
        }}
      >
        <Icon />
      </ListItemIcon>
      <ListItemText
        primary={item.label}
        slotProps={{
          primary: {
            fontSize: 13.5,
            fontWeight: active ? 600 : 500,
            letterSpacing: 0
          }
        }}
      />
    </ListItemButton>
  );
}

/** 左侧 240px 导航：顶部 Compose 式新建按钮 + 胶囊选中态。 */
export default function Sidebar({ onNavigate }) {
  const location = useLocation();
  const navigate = useNavigate();
  const { t } = useI18n();

  const go = path => {
    onNavigate?.();
    navigate(path);
  };

  return (
    <Stack sx={{ height: '100%', px: 1.5, py: 0, bgcolor: googleTokens.sidebar }}>
      <Stack direction="row" spacing={1.1} sx={{ height: 56, px: 1.25, alignItems: 'center' }}>
        <AppLogo size={26} />
        <Typography sx={{ fontSize: 17, fontWeight: 600, letterSpacing: '-0.015em' }}>
          Agent Studio
        </Typography>
      </Stack>

      <Box sx={{ px: 1, pt: 1, pb: 1.5 }}>
        <Button
          fullWidth
          onClick={() => go('/?new=1')}
          startIcon={<AddRounded />}
          sx={{
            height: 44,
            justifyContent: 'flex-start',
            px: 1.75,
            bgcolor: '#FFFFFF',
            color: '#3C4043',
            borderRadius: '14px',
            fontWeight: 500,
            boxShadow: '0 1px 2px rgba(60,64,67,.3), 0 1px 3px 1px rgba(60,64,67,.15)',
            '&:hover': { bgcolor: '#FFFFFF', boxShadow: '0 1px 3px rgba(60,64,67,.35)' }
          }}
        >
          {t('nav.newTask')}
        </Button>
      </Box>

      <Box
        component="nav"
        aria-label={t('nav.aria')}
        sx={{ flex: 1, minHeight: 0, overflowY: 'auto' }}
      >
        <List disablePadding>
          {navigation.map(item => (
            <NavButton
              key={item.path}
              item={{ ...item, label: t(item.key) }}
              active={item.match(location.pathname)}
              onClick={() => go(item.path)}
            />
          ))}
        </List>
      </Box>

      <Box sx={{ pb: 1.5 }}>
        <List disablePadding>
          <NavButton
            item={{
              label: t('nav.settings'),
              path: '/settings',
              icon: SettingsOutlined,
              match: v => v.startsWith('/settings')
            }}
            active={location.pathname.startsWith('/settings')}
            onClick={() => go('/settings')}
          />

          <NavButton
            item={{
              label: t('nav.docs'),
              path: '/docs',
              icon: MenuBookOutlined,
              match: v => v.startsWith('/docs')
            }}
            active={location.pathname.startsWith('/docs')}
            onClick={() => go('/docs')}
          />
        </List>
        <Stack direction="row" spacing={1} sx={{ px: 1.7, pt: 1.5, alignItems: 'center' }}>
          <Box sx={{ width: 7, height: 7, borderRadius: '50%', bgcolor: '#34A853' }} />
          <Typography sx={{ fontSize: 11.5, color: googleTokens.textSecondary }}>
            {t('nav.localWorkbench')}
          </Typography>
        </Stack>
      </Box>
    </Stack>
  );
}
