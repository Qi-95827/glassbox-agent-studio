import {
  Accordion,
  AccordionDetails,
  AccordionSummary,
  Alert,
  Box,
  Button,
  Checkbox,
  FormControlLabel,
  MenuItem,
  Stack,
  TextField,
  Typography
} from '@mui/material';
import ExpandMoreRounded from '@mui/icons-material/ExpandMoreRounded';
import TuneRounded from '@mui/icons-material/TuneRounded';
import { splitLines } from '../../utils/format';
import { googleTokens } from '../../theme/googleTheme';
import { workflowCompatible } from '../bug/bugEntryDefaults';
import AuthorizedFilePicker from '../workspaces/AuthorizedFilePicker';
import RoleBudgetEditor from '../agents/RoleBudgetEditor';
import ToolBudgetEditor from '../tools/ToolBudgetEditor';
import NodeBudgetEditor from '../workflows/NodeBudgetEditor';
import EntryRoutePreview from '../workflows/EntryRoutePreview';
import { useI18n } from '../../i18n/I18nProvider';

const workflows = [
  ['compatibility', true],
  ['dual-analysis', 'text'],
  ['workspace-analysis', 'analysis'],
  ['workspace-change', 'change'],
  ['bug-fix', 'bug']
];

const limits = [
  'rounds',
  'tools',
  'perTool',
  'seconds',
  'maxAgentRounds',
  'maxAgentTools',
  'tokenLimit',
  'identicalLimit'
];

/**
 * 高级运行配置：低频的限额、工作流、授权范围与预算全部折叠。
 * 只编辑任务草稿；兼容性判断只是前端预检，最终由服务端校验。
 */
export default function AdvancedRunConfig({ draft, patch, policy, onOpenBug, onOpenPlan }) {
  const { t } = useI18n();
  const fileScopes = splitLines(draft.fileScopes);
  const directories = splitLines(draft.directories);
  const acceptance = splitLines(draft.acceptance);
  const hasPlannableRoute =
    Boolean(draft.workflowTemplate) ||
    ['CODE', 'TEST', 'REQUIREMENT', 'DESIGN', 'BUG'].includes(draft.entryKind);
  const state = {
    hasWorkspace: Boolean(draft.workspaceId),
    hasBug: Boolean(draft.bugId) || draft.entryKind === 'BUG',
    writeEnabled: Boolean(draft.writeEnabled),
    memoryEnabled: Boolean(draft.memoryEnabled)
  };
  const eligible = {
    text: workflowCompatible('dual-analysis', state),
    analysis: workflowCompatible('workspace-analysis', state),
    change: workflowCompatible('workspace-change', state),
    bug: workflowCompatible('bug-fix', state)
  };
  const mismatch =
    Boolean(draft.workflowTemplate) && !workflowCompatible(draft.workflowTemplate, state);

  return (
    <Accordion sx={{ mt: 1.5, bgcolor: 'transparent', border: 'none' }}>
      <AccordionSummary
        expandIcon={<ExpandMoreRounded fontSize="small" />}
        sx={{ px: 1, minHeight: 42, '& .MuiAccordionSummary-content': { my: 0.75 } }}
      >
        <Stack direction="row" spacing={0.75} sx={{ alignItems: 'center' }}>
          <TuneRounded sx={{ fontSize: 17, color: googleTokens.textSecondary }} />
          <Typography variant="body2" sx={{ fontWeight: 500 }}>
            {t('advanced.title')}
          </Typography>
          <Typography variant="caption" color="text.secondary">
            {t('advanced.subtitle')}
          </Typography>
        </Stack>
      </AccordionSummary>
      <AccordionDetails sx={{ px: 0 }}>
        <Stack spacing={1.75}>
          <Box
            sx={{
              display: 'grid',
              gridTemplateColumns: {
                xs: '1fr',
                sm: 'repeat(2, minmax(0,1fr))',
                lg: 'repeat(3, minmax(0,1fr))'
              },
              gap: 1.5
            }}
          >
            <TextField
              select
              size="small"
              label={t('advanced.workflow')}
              value={draft.workflowTemplate ?? 'compatibility'}
              onChange={event =>
                patch({
                  workflowTemplate:
                    event.target.value === 'compatibility' ? null : event.target.value
                })
              }
            >
              {workflows.map(([value, gate]) => (
                <MenuItem key={value} value={value} disabled={gate !== true && !eligible[gate]}>
                  {t(`advanced.workflow.${value}`)}
                </MenuItem>
              ))}
            </TextField>
            <TextField
              select
              size="small"
              label={t('advanced.scenario')}
              value={draft.scenario}
              onChange={event => patch({ scenario: event.target.value })}
            >
              <MenuItem value="normal">{t('advanced.scenario.normal')}</MenuItem>
              <MenuItem value="fail">{t('advanced.scenario.fail')}</MenuItem>
              <MenuItem value="slow">{t('advanced.scenario.slow')}</MenuItem>
              <MenuItem value="repeat" disabled={draft.model === 'deepseek'}>
                {t('advanced.scenario.repeat')}
              </MenuItem>
            </TextField>
            <TextField
              size="small"
              type="number"
              label={t('advanced.maxCost')}
              value={draft.maxCost}
              onChange={event => patch({ maxCost: event.target.value })}
              placeholder={t('advanced.noLimit')}
            />
          </Box>

          <Box
            sx={{
              display: 'grid',
              gridTemplateColumns: { xs: '1fr', md: 'repeat(2, minmax(0,1fr))' },
              gap: 1.5
            }}
          >
            <TextField
              size="small"
              label={t('advanced.constraints')}
              multiline
              minRows={2}
              value={draft.constraints}
              onChange={event => patch({ constraints: event.target.value })}
              placeholder={t('advanced.constraintsPlaceholder')}
            />
            <TextField
              size="small"
              label={t('advanced.acceptance')}
              multiline
              minRows={2}
              value={draft.acceptance}
              onChange={event => patch({ acceptance: event.target.value })}
              placeholder={t('advanced.acceptancePlaceholder')}
              helperText={t('advanced.acceptanceHint')}
            />
          </Box>

          {draft.workspaceId && (
            <Box
              sx={{
                border: `1px solid ${googleTokens.outline}`,
                borderRadius: 3,
                p: 1.75,
                bgcolor: '#FBFCFD'
              }}
            >
              <Typography sx={{ fontSize: 13, fontWeight: 600, mb: 0.75 }}>
                {t('advanced.scope')}
              </Typography>
              <Stack spacing={1.5}>
                <TextField
                  size="small"
                  label={t('advanced.files')}
                  multiline
                  minRows={2}
                  value={draft.fileScopes}
                  onChange={event => patch({ fileScopes: event.target.value })}
                  placeholder={t('advanced.filesPlaceholder')}
                  helperText={t('advanced.filesHint')}
                />
                <AuthorizedFilePicker
                  workspaceId={draft.workspaceId}
                  value={draft.fileScopes}
                  onChange={next => patch({ fileScopes: next })}
                />
                <Box
                  sx={{
                    display: 'grid',
                    gridTemplateColumns: { xs: '1fr', md: 'repeat(2, minmax(0,1fr))' },
                    gap: 1.5
                  }}
                >
                  <TextField
                    size="small"
                    label={t('advanced.directories')}
                    multiline
                    minRows={2}
                    value={draft.directories}
                    onChange={event => patch({ directories: event.target.value })}
                  />
                  <TextField
                    size="small"
                    label={t('advanced.exclusions')}
                    multiline
                    minRows={2}
                    value={draft.exclusions}
                    onChange={event => patch({ exclusions: event.target.value })}
                  />
                </Box>
                <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1}>
                  <FormControlLabel
                    control={
                      <Checkbox
                        size="small"
                        checked={draft.writeEnabled}
                        onChange={event => patch({ writeEnabled: event.target.checked })}
                      />
                    }
                    label={<Typography variant="body2">{t('advanced.allowWrite')}</Typography>}
                  />
                  <FormControlLabel
                    control={
                      <Checkbox
                        size="small"
                        checked={draft.memoryEnabled}
                        onChange={event => patch({ memoryEnabled: event.target.checked })}
                      />
                    }
                    label={<Typography variant="body2">{t('advanced.memory')}</Typography>}
                  />
                </Stack>
                {draft.writeEnabled && (
                  <Alert severity="warning">{t('advanced.writeWarning')}</Alert>
                )}
              </Stack>
            </Box>
          )}

          {draft.entryKind === 'BUG' && (
            <Stack direction="row" spacing={1} sx={{ alignItems: 'center', flexWrap: 'wrap' }}>
              <TextField
                size="small"
                label={t('advanced.bug')}
                value={draft.bugId}
                onChange={event => patch({ bugId: event.target.value })}
                sx={{ minWidth: 220 }}
              />
              <Button variant="outlined" onClick={onOpenBug}>
                {t('advanced.openBug')}
              </Button>
            </Stack>
          )}

          <Box
            sx={{
              display: 'grid',
              gridTemplateColumns: {
                xs: '1fr',
                sm: 'repeat(2, minmax(0,1fr))',
                lg: 'repeat(4, minmax(0,1fr))'
              },
              gap: 1.5
            }}
          >
            {limits.map(key => (
              <TextField
                key={key}
                size="small"
                type="number"
                label={t(`advanced.limit.${key}`)}
                value={draft[key]}
                onChange={event => patch({ [key]: event.target.value })}
                placeholder={['rounds', 'tools'].includes(key) ? t('advanced.noLimit') : undefined}
              />
            ))}
          </Box>

          <RoleBudgetEditor
            value={draft.roleBudgets}
            onChange={value => patch({ roleBudgets: value })}
          />
          <ToolBudgetEditor
            value={draft.toolBudgets}
            onChange={value => patch({ toolBudgets: value })}
          />
          <NodeBudgetEditor
            template={draft.workflowTemplate}
            value={draft.nodeBudgets}
            onChange={value => patch({ nodeBudgets: value })}
          />

          {hasPlannableRoute && (
            <FormControlLabel
              control={
                <Checkbox
                  size="small"
                  checked={draft.reviewPlan}
                  onChange={event => patch({ reviewPlan: event.target.checked })}
                />
              }
              label={<Typography variant="body2">{t('advanced.reviewPlan')}</Typography>}
            />
          )}

          {policy && (
            <Box
              sx={{
                p: 1.75,
                borderRadius: 3,
                bgcolor: '#FBFCFD',
                border: '1px solid',
                borderColor: 'divider'
              }}
            >
              <Typography variant="body2" sx={{ fontWeight: 600, mb: 0.4 }}>
                入口约定 · {policy.label}
              </Typography>
              <Typography variant="caption" color="text.secondary" sx={{ display: 'block' }}>
                必填：{policy.requiredInputs.join('；')}
              </Typography>
              <Typography variant="caption" color="text.secondary" sx={{ display: 'block' }}>
                交付物：{policy.deliverables.join('；')}
              </Typography>
              <Typography variant="caption" color="text.secondary" sx={{ display: 'block' }}>
                默认验收：{policy.defaultAcceptance.join('；')}
              </Typography>
            </Box>
          )}

          {mismatch && (
            <Alert severity="warning">
              当前任务设置与所选工作流不兼容，请调整工作区或写入授权。
            </Alert>
          )}

          <EntryRoutePreview
            entryKind={draft.entryKind}
            workspaceId={draft.workspaceId}
            writeEnabled={draft.writeEnabled}
            fileScopes={fileScopes}
            directoryScopes={directories}
            workflowTemplate={draft.workflowTemplate}
          />

          {draft.entryKind === 'CODE' &&
            draft.workspaceId &&
            fileScopes.length > 0 &&
            acceptance.length > 0 && (
              <Button variant="outlined" onClick={onOpenPlan} sx={{ alignSelf: 'flex-start' }}>
                生成执行计划草稿
              </Button>
            )}
        </Stack>
      </AccordionDetails>
    </Accordion>
  );
}
