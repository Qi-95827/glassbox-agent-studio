import { Box, Button, Stack, Tab, Tabs } from '@mui/material';
import PauseRounded from '@mui/icons-material/PauseRounded';
import PlayArrowRounded from '@mui/icons-material/PlayArrowRounded';
import TaskAltRounded from '@mui/icons-material/TaskAltRounded';
import ReplayRounded from '@mui/icons-material/ReplayRounded';
import MoreVertRounded from '@mui/icons-material/MoreVertRounded';
import { useState } from 'react';
import { IconButton, Menu, MenuItem } from '@mui/material';
import { googleTokens } from '../../theme/googleTheme';

const actionIcons = {
  PAUSE: <PauseRounded fontSize="small" />,
  RESUME: <PlayArrowRounded fontSize="small" />,
  RECOVER: <ReplayRounded fontSize="small" />
};

const primaryActions = new Set(['APPROVE_PLAN', 'PAUSE', 'RESUME', 'TAKE_OVER', 'RETURN_HANDOVER']);
const secondaryActions = new Set(['CANCEL', 'RECOVER']);

/** 运行顶部：蓝色细底条 Tabs + 服务端动作按钮组。 */
export default function RunHeader({
  view,
  tab,
  onTab,
  onAction,
  busy,
  workspaceId,
  onOpenWorkspace
}) {
  const [moreAnchor, setMoreAnchor] = useState(null);
  const actions = (view?.availableActions ?? []).filter(
    action => action.actionCode !== 'SUBMIT_SUPPLEMENT'
  );
  const inline = actions.filter(action => primaryActions.has(action.actionCode));
  const more = actions.filter(action => secondaryActions.has(action.actionCode));

  return (
    <Box
      sx={{
        flexShrink: 0,
        display: 'flex',
        alignItems: 'center',
        gap: 1,
        px: { xs: 1, sm: 2 },
        bgcolor: '#FFFFFF',
        borderBottom: `1px solid ${googleTokens.outline}`
      }}
    >
      <Tabs
        value={tab}
        onChange={(_, value) => onTab(value)}
        variant="scrollable"
        scrollButtons={false}
        aria-label="运行视图切换"
        sx={{ flex: 1, minWidth: 0, '& .MuiTabs-indicator': { bgcolor: googleTokens.primary } }}
      >
        <Tab value="flow" label="观察流程" />
        <Tab value="replay" label="逐步回放" />
        <Tab value="output" label="交付说明" />
        <Tab value="list" label="列表视图" />
      </Tabs>

      <Stack direction="row" spacing={0.9} sx={{ flexShrink: 0, alignItems: 'center' }}>
        {inline.map(action => (
          <Button
            key={action.actionCode}
            size="small"
            variant={action.actionCode === 'APPROVE_PLAN' ? 'contained' : 'outlined'}
            startIcon={actionIcons[action.actionCode]}
            disabled={!action.enabled || busy}
            title={action.disabledReason || ''}
            onClick={() => onAction(action)}
            sx={{ display: { xs: 'none', md: 'inline-flex' } }}
          >
            {action.label}
            {action.targetLabel && action.actionCode !== 'APPROVE_PLAN'
              ? ` · ${action.targetLabel}`
              : ''}
          </Button>
        ))}
        {more.length > 0 && (
          <>
            <IconButton
              size="small"
              aria-label="更多运行操作"
              onClick={event => setMoreAnchor(event.currentTarget)}
            >
              <MoreVertRounded fontSize="small" />
            </IconButton>
            <Menu
              anchorEl={moreAnchor}
              open={Boolean(moreAnchor)}
              onClose={() => setMoreAnchor(null)}
              anchorOrigin={{ vertical: 'bottom', horizontal: 'right' }}
            >
              {more.map(action => (
                <MenuItem
                  key={action.actionCode}
                  disabled={!action.enabled || busy}
                  onClick={() => {
                    setMoreAnchor(null);
                    onAction(action);
                  }}
                >
                  {action.label}
                  {action.targetLabel ? ` · ${action.targetLabel}` : ''}
                </MenuItem>
              ))}
            </Menu>
          </>
        )}
        <Button
          size="small"
          variant="contained"
          startIcon={<TaskAltRounded fontSize="small" />}
          onClick={() => {
            if (tab !== 'output') {
              onTab('output');
            } else if (workspaceId && onOpenWorkspace) {
              onOpenWorkspace();
            } else {
              onTab('output');
            }
          }}
          sx={{ whiteSpace: 'nowrap' }}
        >
          {tab === 'output' && workspaceId ? '去工作区提交' : '验收交付'}
        </Button>
      </Stack>
    </Box>
  );
}
