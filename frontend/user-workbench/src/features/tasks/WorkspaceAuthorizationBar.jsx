import {
  Box,
  Checkbox,
  FormControlLabel,
  Stack,
  TextField,
  ToggleButton,
  ToggleButtonGroup,
  Typography
} from '@mui/material';
import LockOpenOutlined from '@mui/icons-material/LockOpenOutlined';
import { googleTokens } from '../../theme/googleTheme';
import { useI18n } from '../../i18n/I18nProvider';
import AuthorizedFilePicker from '../workspaces/AuthorizedFilePicker';

/** 工作区授权范围：整个工作区 / 指定子目录 / 指定文件。 */
export const SCOPE_WORKSPACE = 'WORKSPACE';
export const SCOPE_DIRECTORY = 'DIRECTORY';
export const SCOPE_FILES = 'FILES';

const hints = {
  [SCOPE_WORKSPACE]: 'composer.authMode.workspaceHint',
  [SCOPE_DIRECTORY]: 'composer.authMode.directoryHint',
  [SCOPE_FILES]: 'composer.authMode.filesHint'
};

/**
 * 主卡片下方的授权状态条：三档一键模式 + 允许修改 + 读取项目记忆。
 *
 * <p>只有切到「指定子目录」或「指定文件」时才就地展开紧凑输入，避免默认视图出现多层表单。
 */
export default function WorkspaceAuthorizationBar({ draft, patch }) {
  const { t } = useI18n();
  const mode = draft.authMode || SCOPE_WORKSPACE;

  return (
    <Box
      sx={{
        mt: 1.5,
        pt: 1.5,
        borderTop: `1px solid ${googleTokens.outline}`,
        display: 'flex',
        flexDirection: 'column',
        gap: 1
      }}
    >
      <Stack
        direction="row"
        spacing={1}
        useFlexGap
        sx={{ flexWrap: 'wrap', alignItems: 'center', rowGap: 0.75 }}
      >
        <ToggleButtonGroup
          exclusive
          size="small"
          value={mode}
          onChange={(_, value) => value && patch({ authMode: value })}
          aria-label={t('composer.authMode.aria')}
        >
          <ToggleButton value={SCOPE_WORKSPACE} sx={{ px: 1.4, py: 0.25, fontSize: 12.5 }}>
            {t('composer.authMode.workspace')}
          </ToggleButton>
          <ToggleButton value={SCOPE_DIRECTORY} sx={{ px: 1.4, py: 0.25, fontSize: 12.5 }}>
            {t('composer.authMode.directory')}
          </ToggleButton>
          <ToggleButton value={SCOPE_FILES} sx={{ px: 1.4, py: 0.25, fontSize: 12.5 }}>
            {t('composer.authMode.files')}
          </ToggleButton>
        </ToggleButtonGroup>

        <FormControlLabel
          sx={{ m: 0 }}
          control={
            <Checkbox
              size="small"
              checked={Boolean(draft.writeEnabled)}
              onChange={event => patch({ writeEnabled: event.target.checked })}
            />
          }
          label={<Typography variant="body2">{t('composer.authMode.allowWrite')}</Typography>}
        />
        <FormControlLabel
          sx={{ m: 0 }}
          control={
            <Checkbox
              size="small"
              checked={Boolean(draft.memoryEnabled)}
              onChange={event => patch({ memoryEnabled: event.target.checked })}
            />
          }
          label={<Typography variant="body2">{t('composer.authMode.memory')}</Typography>}
        />
        <Stack direction="row" spacing={0.5} sx={{ alignItems: 'center', ml: 'auto' }}>
          <LockOpenOutlined
            sx={{
              fontSize: 15,
              color: draft.writeEnabled ? googleTokens.primary : googleTokens.textSecondary
            }}
          />
          <Typography variant="caption" color="text.secondary">
            {draft.writeEnabled ? t('composer.authMode.writeOn') : t('composer.authMode.writeOff')}
          </Typography>
        </Stack>
      </Stack>

      <Typography variant="caption" color="text.secondary">
        {t(hints[mode])}
      </Typography>

      {mode === SCOPE_DIRECTORY && (
        <Box
          sx={{
            display: 'grid',
            gridTemplateColumns: { xs: '1fr', sm: 'repeat(2, minmax(0,1fr))' },
            gap: 1.25
          }}
        >
          <TextField
            size="small"
            label={t('composer.authMode.directories')}
            multiline
            minRows={2}
            value={draft.directories}
            onChange={event => patch({ directories: event.target.value })}
            placeholder={t('composer.authMode.directoriesPlaceholder')}
          />
          <TextField
            size="small"
            label={t('composer.authMode.exclusions')}
            multiline
            minRows={2}
            value={draft.exclusions}
            onChange={event => patch({ exclusions: event.target.value })}
            placeholder={t('composer.authMode.exclusionsPlaceholder')}
          />
        </Box>
      )}

      {mode === SCOPE_FILES && (
        <Stack spacing={1}>
          <TextField
            size="small"
            label={t('composer.authMode.filesLabel')}
            multiline
            minRows={2}
            value={draft.fileScopes}
            onChange={event => patch({ fileScopes: event.target.value })}
            placeholder={t('composer.authMode.filesPlaceholder')}
          />
          <AuthorizedFilePicker
            workspaceId={draft.workspaceId}
            value={draft.fileScopes}
            onChange={next => patch({ fileScopes: next })}
          />
        </Stack>
      )}
    </Box>
  );
}
