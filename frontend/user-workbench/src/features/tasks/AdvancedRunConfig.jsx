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
import SavingsOutlined from '@mui/icons-material/SavingsOutlined';
import TuneRounded from '@mui/icons-material/TuneRounded';
import { splitLines } from '../../utils/format';
import { googleTokens } from '../../theme/googleTheme';
import { workflowCompatible } from '../bug/bugEntryDefaults';
import RoleBudgetEditor from '../agents/RoleBudgetEditor';
import ToolBudgetEditor from '../tools/ToolBudgetEditor';
import NodeBudgetEditor from '../workflows/NodeBudgetEditor';
import EntryRoutePreview from '../workflows/EntryRoutePreview';
import { useI18n } from '../../i18n/I18nProvider';
import { SCOPE_WORKSPACE } from './WorkspaceAuthorizationBar';

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
 * 高级运行配置：工作流、约束、验收条件与低频预算。
 *
 * <p>授权范围与写入开关在主卡片下方的状态条，这里不再重复；8 个数值限额和三类预算编辑收进二级折叠，
 * 默认收起，展开高级配置时也保持清爽。
 */
export default function AdvancedRunConfig({ draft, patch, policy, onOpenBug, onOpenPlan }) {
  const { t } = useI18n();
  const directories = splitLines(draft.directories);
  const fileScopes = splitLines(draft.fileScopes);
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
  const scopeReady =
    draft.authMode === SCOPE_WORKSPACE || fileScopes.length > 0 || directories.length > 0;

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

          {draft.writeEnabled && draft.workspaceId && (
            <Alert severity="warning">{t('advanced.writeWarning')}</Alert>
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

          <Accordion
            disableGutters
            sx={{
              border: '1px solid',
              borderColor: 'divider',
              borderRadius: 2.5,
              boxShadow: 'none',
              bgcolor: '#FBFCFD',
              '&:before': { display: 'none' }
            }}
          >
            <AccordionSummary
              expandIcon={<ExpandMoreRounded fontSize="small" />}
              aria-label={t('advanced.budget.title')}
            >
              <Stack direction="row" spacing={0.75} sx={{ alignItems: 'center' }}>
                <SavingsOutlined sx={{ fontSize: 17, color: googleTokens.textSecondary }} />
                <Stack sx={{ minWidth: 0 }}>
                  <Typography variant="body2" sx={{ fontWeight: 500 }}>
                    {t('advanced.budget.title')}
                  </Typography>
                  <Typography variant="caption" color="text.secondary">
                    {t('advanced.budget.subtitle')}
                  </Typography>
                </Stack>
              </Stack>
            </AccordionSummary>
            <AccordionDetails>
              <Stack spacing={1.5}>
                <Typography variant="caption" color="text.secondary">
                  {t('advanced.budget.note')}
                </Typography>
                <Typography variant="caption" sx={{ fontWeight: 600 }}>
                  {t('advanced.budget.limits')}
                </Typography>
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
                      placeholder={
                        ['rounds', 'tools'].includes(key) ? t('advanced.noLimit') : undefined
                      }
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
              </Stack>
            </AccordionDetails>
          </Accordion>

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
                {t('advanced.entryPolicy.title', { label: policy.label })}
              </Typography>
              <Typography variant="caption" color="text.secondary" sx={{ display: 'block' }}>
                {t('advanced.entryPolicy.required', { items: policy.requiredInputs.join('；') })}
              </Typography>
              <Typography variant="caption" color="text.secondary" sx={{ display: 'block' }}>
                {t('advanced.entryPolicy.deliverables', {
                  items: policy.deliverables.join('；')
                })}
              </Typography>
              <Typography variant="caption" color="text.secondary" sx={{ display: 'block' }}>
                {t('advanced.entryPolicy.acceptance', {
                  items: policy.defaultAcceptance.join('；')
                })}
              </Typography>
            </Box>
          )}

          {mismatch && <Alert severity="warning">{t('advanced.workflowMismatch')}</Alert>}

          <EntryRoutePreview
            entryKind={draft.entryKind}
            workspaceId={draft.workspaceId}
            writeEnabled={draft.writeEnabled}
            fileScopes={fileScopes}
            directoryScopes={draft.authMode === SCOPE_WORKSPACE ? ['.'] : directories}
            workflowTemplate={draft.workflowTemplate}
          />

          {draft.entryKind === 'CODE' &&
            draft.workspaceId &&
            scopeReady &&
            acceptance.length > 0 && (
              <Button variant="outlined" onClick={onOpenPlan} sx={{ alignSelf: 'flex-start' }}>
                {t('advanced.openPlanDraft')}
              </Button>
            )}
        </Stack>
      </AccordionDetails>
    </Accordion>
  );
}
