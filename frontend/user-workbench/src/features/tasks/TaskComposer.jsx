import { useEffect, useReducer, useRef, useState } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import {
  Box,
  Button,
  ButtonBase,
  Divider,
  Menu,
  MenuItem,
  Stack,
  TextField,
  ToggleButton,
  ToggleButtonGroup,
  Typography
} from '@mui/material';
import AutoAwesomeRounded from '@mui/icons-material/AutoAwesomeRounded';
import ExpandMoreRounded from '@mui/icons-material/ExpandMoreRounded';
import FolderOpenOutlined from '@mui/icons-material/FolderOpenOutlined';
import ChevronRightRounded from '@mui/icons-material/ChevronRightRounded';
import BugReportOutlined from '@mui/icons-material/BugReportOutlined';
import AddRounded from '@mui/icons-material/AddRounded';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { tasksApi } from '../../api/tasks';
import { modelSelectionLabel, parseModelValue } from '../../api/modelConnections';
import { useModelConnections } from '../../hooks/useModelConnections';
import { useWorkspaces } from '../../hooks/useWorkspaces';
import { useEntryPolicies } from '../../hooks/useTasks';
import { useToast } from '../../components/ToastProvider';
import { numberOrNull, splitLines } from '../../utils/format';
import { entryKindLabel } from '../../constants/labels';
import { workflowCompatible } from '../bug/bugEntryDefaults';
import TaskIdeaDialog from './TaskIdeaDialog';
import PlanCandidateDialog from './PlanCandidateDialog';
import AdvancedRunConfig from './AdvancedRunConfig';
import NewWorkspaceDialog from '../workspaces/NewWorkspaceDialog';
import BugVerifyDialog from '../bug/BugVerifyDialog';
import { googleTokens } from '../../theme/googleTheme';
import { useI18n } from '../../i18n/I18nProvider';

const entryOptions = ['GENERAL', 'REQUIREMENT', 'DESIGN', 'CODE', 'TEST', 'BUG'];

const initialDraft = {
  entryKind: 'CODE',
  goal: '',
  model: 'mock',
  scenario: 'normal',
  workflowTemplate: null,
  reviewPlan: true,
  workspaceId: '',
  fileScopes: '',
  directories: '',
  exclusions: '',
  writeEnabled: false,
  memoryEnabled: false,
  bugId: 'MOCK-PY-001',
  rounds: '',
  tools: '',
  perTool: '',
  seconds: '',
  tokenLimit: '',
  identicalLimit: '',
  maxAgentRounds: '',
  maxAgentTools: '',
  maxCost: '',
  constraints: '',
  acceptance: '',
  roleBudgets: {},
  toolBudgets: {},
  nodeBudgets: {}
};

function draftReducer(state, action) {
  return { ...state, ...action };
}

function ConfigChip({ icon, label, onClick, strong }) {
  return (
    <ButtonBase
      onClick={onClick}
      sx={{
        height: 30,
        px: 1.25,
        borderRadius: 9999,
        border: `1px solid ${strong ? googleTokens.primarySoft : googleTokens.outlineStrong}`,
        bgcolor: strong ? '#F5F9FF' : '#FFFFFF',
        color: strong ? '#1967D2' : '#3C4043',
        gap: 0.6,
        fontSize: 12.5,
        maxWidth: '100%'
      }}
    >
      {icon}
      <Box
        component="span"
        sx={{
          whiteSpace: 'nowrap',
          overflow: 'hidden',
          textOverflow: 'ellipsis',
          maxWidth: 220
        }}
      >
        {label}
      </Box>
      <ExpandMoreRounded sx={{ fontSize: 16, color: googleTokens.textSecondary }} />
    </ButtonBase>
  );
}

export default function TaskComposer({ onCreated, onModelChange }) {
  const { t, locale } = useI18n();
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const toast = useToast();
  const queryClient = useQueryClient();
  const workspaces = useWorkspaces();
  const policies = useEntryPolicies();
  const connections = useModelConnections();
  const goalRef = useRef(null);
  const focusGoal = searchParams.get('new') === '1';
  const [draft, update] = useReducer(draftReducer, initialDraft);
  const [sending, setSending] = useState(false);
  const [ideaOpen, setIdeaOpen] = useState(false);
  const [planOpen, setPlanOpen] = useState(false);
  const [workspaceDialog, setWorkspaceDialog] = useState(false);
  const [bugOpen, setBugOpen] = useState(false);
  const [modelMenu, setModelMenu] = useState(null);
  const [workspaceMenu, setWorkspaceMenu] = useState(null);

  const workspaceList = workspaces.data ?? [];
  const connectionList = connections.data?.connections ?? [];
  const builtInModels = connections.data?.builtIns ?? [];
  const legacyDeepSeek = builtInModels.some(item => item.id === 'legacy-deepseek');
  const selection = parseModelValue(draft.model);
  const workspace = workspaceList.find(item => item.id === draft.workspaceId) ?? null;
  const policy = (policies.data ?? []).find(item => item.kind === draft.entryKind);
  const hasPlannableRoute =
    Boolean(draft.workflowTemplate) ||
    ['CODE', 'TEST', 'REQUIREMENT', 'DESIGN', 'BUG'].includes(draft.entryKind);
  const fileScopes = splitLines(draft.fileScopes);
  const directories = splitLines(draft.directories);
  const constraints = splitLines(draft.constraints);
  const acceptanceCriteria = splitLines(draft.acceptance);
  const workspaceState = {
    hasWorkspace: Boolean(draft.workspaceId),
    hasBug: Boolean(draft.bugId) || draft.entryKind === 'BUG',
    writeEnabled: Boolean(draft.writeEnabled),
    memoryEnabled: Boolean(draft.memoryEnabled)
  };
  const workflowMismatch =
    Boolean(draft.workflowTemplate) && !workflowCompatible(draft.workflowTemplate, workspaceState);

  const patch = value => update(value);

  useEffect(() => {
    // 顶栏只显示模型类别，不暴露具体连接标识。
    onModelChange?.(selection.modelConnectionId ? 'user' : draft.model);
  }, [draft.model, selection.modelConnectionId, onModelChange]);

  const create = async () => {
    if (!draft.goal.trim()) {
      toast.warning(t('composer.goalRequired'));
      goalRef.current?.focus();
      return;
    }
    if (draft.entryKind === 'BUG' && !draft.workspaceId) {
      toast.warning(t('composer.bugWorkspaceRequired'));
      return;
    }
    if (workflowMismatch) {
      toast.warning(t('composer.workflowMismatch'));
      return;
    }
    setSending(true);
    try {
      const result = await tasksApi.create({
        goal: draft.goal.trim(),
        entryKind: draft.entryKind,
        workflowTemplate: draft.workflowTemplate,
        reviewPlan: hasPlannableRoute && draft.reviewPlan,
        modelMode: selection.modelMode,
        modelConnectionId: selection.modelConnectionId,
        scenario: draft.scenario,
        maxRounds: numberOrNull(draft.rounds),
        maxTools: numberOrNull(draft.tools),
        maxPerTool: numberOrNull(draft.perTool),
        maxReportedTokens: numberOrNull(draft.tokenLimit),
        maxIdenticalTools: numberOrNull(draft.identicalLimit),
        maxAgentRounds: numberOrNull(draft.maxAgentRounds),
        maxAgentTools: numberOrNull(draft.maxAgentTools),
        roleBudgets: draft.roleBudgets,
        toolBudgets: draft.toolBudgets,
        nodeBudgets: draft.nodeBudgets,
        maxSeconds: numberOrNull(draft.seconds),
        workspaceId: draft.workspaceId || null,
        fileScope: null,
        fileScopes: draft.workspaceId ? fileScopes : null,
        directoryScopes: draft.workspaceId ? directories : null,
        excludedPaths: draft.workspaceId ? splitLines(draft.exclusions) : null,
        maxCostMicros: numberOrNull(draft.maxCost),
        writeEnabled: Boolean(draft.workspaceId && draft.writeEnabled),
        bugId: draft.entryKind === 'BUG' ? draft.bugId : null,
        memoryEnabled: Boolean(draft.workspaceId && draft.memoryEnabled),
        constraints,
        acceptanceCriteria,
        locale
      });
      queryClient.invalidateQueries({ queryKey: ['tasks'] });
      toast.success(draft.reviewPlan ? t('composer.createdReview') : t('composer.createdRun'));
      if (onCreated) onCreated(result.runId);
      else navigate(`/runs/${result.runId}`);
    } catch (error) {
      toast.error(error.message);
    } finally {
      setSending(false);
    }
  };

  const applyManualDraft = value => {
    if (!value) return;
    patch({
      goal: value.goal ?? draft.goal,
      entryKind: value.entryKind ?? draft.entryKind,
      constraints: (value.constraints ?? []).join('\n'),
      acceptance: (value.acceptanceCriteria ?? []).join('\n'),
      reviewPlan: value.reviewPlan ?? draft.reviewPlan,
      model: value.modelConnectionId
        ? `user:${value.modelConnectionId}`
        : (value.modelMode ?? draft.model),
      workflowTemplate: value.entryKind === 'GENERAL' && value.reviewPlan ? 'dual-analysis' : null,
      workspaceId: '',
      fileScopes: '',
      writeEnabled: false,
      memoryEnabled: false,
      bugId: null
    });
  };

  return (
    <Box>
      <Box sx={{ mb: 2 }}>
        <ToggleButtonGroup
          exclusive
          size="small"
          value={draft.entryKind}
          onChange={(_, value) => value && patch({ entryKind: value })}
          aria-label={t('composer.entryAria')}
          sx={{ flexWrap: 'wrap', rowGap: 0.5 }}
        >
          {entryOptions.map(value => (
            <ToggleButton key={value} value={value} sx={{ px: 1.6 }}>
              {entryKindLabel(value, locale)}
            </ToggleButton>
          ))}
        </ToggleButtonGroup>
      </Box>

      <Box
        sx={{
          bgcolor: '#FFFFFF',
          border: `1px solid ${googleTokens.outline}`,
          borderRadius: 6,
          p: { xs: 1.5, sm: 2 },
          boxShadow: '0 1px 2px rgba(60,64,67,.06)'
        }}
      >
        <TextField
          inputRef={goalRef}
          autoFocus={focusGoal}
          fullWidth
          multiline
          minRows={4}
          maxRows={12}
          placeholder={t('composer.goalPlaceholder')}
          value={draft.goal}
          onChange={event => patch({ goal: event.target.value })}
          variant="standard"
          slotProps={{
            input: { disableUnderline: true, sx: { fontSize: 15.5, lineHeight: 1.7 } },
            htmlInput: { maxLength: 2500 }
          }}
        />

        <Divider sx={{ my: 1.5 }} />
        <Stack
          direction={{ xs: 'column', sm: 'row' }}
          spacing={1}
          sx={{ alignItems: { xs: 'stretch', sm: 'center' }, justifyContent: 'space-between' }}
        >
          <Stack direction="row" spacing={0.75} useFlexGap sx={{ flexWrap: 'wrap' }}>
            <ConfigChip
              icon={<AutoAwesomeRounded sx={{ fontSize: 16, color: '#1A73E8' }} />}
              label={modelSelectionLabel(connections.data, draft.model, locale)}
              strong={Boolean(selection.modelConnectionId)}
              onClick={event => setModelMenu(event.currentTarget)}
            />

            <ConfigChip
              icon={<FolderOpenOutlined sx={{ fontSize: 16, color: '#5F6368' }} />}
              label={workspace ? workspace.branchName : t('composer.chooseWorkspace')}
              strong={Boolean(workspace)}
              onClick={event => setWorkspaceMenu(event.currentTarget)}
            />

            {draft.entryKind === 'BUG' && (
              <ConfigChip
                icon={<BugReportOutlined sx={{ fontSize: 16, color: '#B06000' }} />}
                label={draft.bugId}
                onClick={() => setBugOpen(true)}
              />
            )}
          </Stack>
          <Stack direction="row" spacing={1} sx={{ flexShrink: 0, pt: { xs: 0.5, sm: 0 } }}>
            <Button
              variant="outlined"
              startIcon={<AutoAwesomeRounded />}
              onClick={() => setIdeaOpen(true)}
              sx={{ whiteSpace: 'nowrap' }}
            >
              {t('composer.organizeIdea')}
            </Button>
            <Button
              variant="contained"
              onClick={create}
              loading={sending}
              endIcon={<ChevronRightRounded />}
            >
              {hasPlannableRoute && draft.reviewPlan
                ? t('composer.generatePlan')
                : t('composer.startRun')}
            </Button>
          </Stack>
        </Stack>
      </Box>

      <Menu
        anchorEl={modelMenu}
        open={Boolean(modelMenu)}
        onClose={() => setModelMenu(null)}
        anchorOrigin={{ vertical: 'bottom', horizontal: 'left' }}
      >
        <MenuItem
          selected={draft.model === 'mock'}
          onClick={() => {
            patch({ model: 'mock' });
            setModelMenu(null);
          }}
        >
          {t('composer.mockModel')}
        </MenuItem>
        {legacyDeepSeek && (
          <MenuItem
            selected={draft.model === 'deepseek'}
            onClick={() => {
              patch({ model: 'deepseek' });
              setModelMenu(null);
            }}
          >
            {t('composer.deepseekFile')}
          </MenuItem>
        )}
        <Divider />
        <Typography
          variant="caption"
          color="text.secondary"
          sx={{ display: 'block', px: 2, py: 0.5 }}
        >
          {t('composer.myModels')}
        </Typography>
        {connectionList.length === 0 && (
          <Typography
            variant="caption"
            color="text.secondary"
            sx={{ display: 'block', px: 2, py: 0.5 }}
          >
            {t('composer.noConnections')}
          </Typography>
        )}
        {connectionList.map(connection => (
          <MenuItem
            key={connection.id}
            disabled={!connection.enabled}
            selected={selection.modelConnectionId === connection.id}
            onClick={() => {
              patch({ model: `user:${connection.id}` });
              setModelMenu(null);
            }}
            sx={{ maxWidth: 380 }}
          >
            <Stack sx={{ minWidth: 0 }}>
              <Typography sx={{ fontSize: 13 }} noWrap>
                {connection.displayName}
                {connection.isDefault ? ` · ${t('common.default')}` : ''}
              </Typography>
              <Typography variant="caption" color="text.secondary" noWrap>
                {connection.modelName} · {connection.keyMasked}
              </Typography>
            </Stack>
          </MenuItem>
        ))}
        <Divider />
        <MenuItem
          onClick={() => {
            setModelMenu(null);
            navigate('/settings');
          }}
        >
          <AddRounded fontSize="small" sx={{ mr: 1 }} /> {t('composer.configureKey')}
        </MenuItem>
      </Menu>

      <Menu
        anchorEl={workspaceMenu}
        open={Boolean(workspaceMenu)}
        onClose={() => setWorkspaceMenu(null)}
        anchorOrigin={{ vertical: 'bottom', horizontal: 'left' }}
      >
        {workspaceList.map(item => (
          <MenuItem
            key={item.id}
            selected={draft.workspaceId === item.id}
            onClick={() => {
              patch({
                workspaceId: item.id,
                writeEnabled: false,
                memoryEnabled: false,
                bugId: null
              });
              setWorkspaceMenu(null);
            }}
            sx={{ maxWidth: 360 }}
          >
            <Stack sx={{ minWidth: 0 }}>
              <Typography sx={{ fontSize: 13 }} noWrap>
                {item.branchName}
              </Typography>
              <Typography variant="caption" color="text.secondary" noWrap>
                {item.repositoryPath}
              </Typography>
            </Stack>
          </MenuItem>
        ))}
        {workspaceList.length > 0 && <Divider />}
        <MenuItem
          onClick={() => {
            setWorkspaceMenu(null);
            setWorkspaceDialog(true);
          }}
        >
          <AddRounded fontSize="small" sx={{ mr: 1 }} /> {t('composer.newWorkspace')}
        </MenuItem>
        {draft.workspaceId && (
          <MenuItem
            onClick={() => {
              patch({ workspaceId: '', writeEnabled: false, memoryEnabled: false, bugId: null });
              setWorkspaceMenu(null);
            }}
          >
            {t('composer.noWorkspace')}
          </MenuItem>
        )}
      </Menu>

      <AdvancedRunConfig
        draft={draft}
        patch={patch}
        policy={policy}
        onOpenBug={() => setBugOpen(true)}
        onOpenPlan={() => setPlanOpen(true)}
      />

      <TaskIdeaDialog
        open={ideaOpen}
        initialIdea={draft.goal}
        initialModelMode={selection.modelMode}
        initialModelConnectionId={selection.modelConnectionId}
        onClose={() => setIdeaOpen(false)}
        onManual={value => {
          applyManualDraft(value);
          setIdeaOpen(false);
        }}
        onCreated={runId => {
          queryClient.invalidateQueries({ queryKey: ['tasks'] });
          if (onCreated) onCreated(runId);
          else navigate(`/runs/${runId}`);
        }}
      />

      <PlanCandidateDialog
        open={planOpen}
        onClose={() => setPlanOpen(false)}
        maxCostMicros={numberOrNull(draft.maxCost)}
        onRun={runId => {
          setPlanOpen(false);
          queryClient.invalidateQueries({ queryKey: ['tasks'] });
          if (onCreated) onCreated(runId);
          else navigate(`/runs/${runId}`);
        }}
        input={{
          goal: draft.goal.trim(),
          entryKind: 'CODE',
          workspaceId: draft.workspaceId,
          writeEnabled: draft.writeEnabled,
          readOnly: !draft.writeEnabled,
          agentMode: 'BUILTIN',
          candidateAgentIds: [],
          manualAgentId: null,
          manualAgentVersion: null,
          fileScopes,
          constraints,
          acceptanceCriteria,
          deliverable: null,
          modelConnectionId: selection.modelConnectionId,
          locale
        }}
      />

      <NewWorkspaceDialog
        open={workspaceDialog}
        onClose={() => setWorkspaceDialog(false)}
        onCreated={created => patch({ workspaceId: created.id })}
      />

      <BugVerifyDialog
        open={bugOpen}
        onClose={() => setBugOpen(false)}
        initialWorkspaceId={draft.workspaceId}
        onCreateTask={value =>
          patch({
            entryKind: 'BUG',
            workspaceId: value.workspaceId,
            bugId: value.bugId,
            goal: value.goal,
            workflowTemplate: 'bug-fix',
            reviewPlan: true,
            writeEnabled: false,
            memoryEnabled: false
          })
        }
      />
    </Box>
  );
}
