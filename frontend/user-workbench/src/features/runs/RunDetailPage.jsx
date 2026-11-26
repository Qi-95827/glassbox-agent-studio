import { useEffect, useMemo, useRef, useState } from 'react';
import {
  Alert,
  Box,
  Button,
  CircularProgress,
  Drawer,
  Stack,
  Typography,
  useMediaQuery
} from '@mui/material';
import { useLocation, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { useQueryClient } from '@tanstack/react-query';
import RunHeader from './RunHeader';
import RunFlowGraph from '../workflows/RunFlowGraph';
import ToolApprovalDrawer from './ToolApprovalDrawer';
import RunReplayPanel from './RunReplayPanel';
import RunOutputsPanel from './RunOutputsPanel';
import RunListView from './RunListView';
import NodeInspector from './NodeInspector';
import RunDiagnosticsDrawer from './RunDiagnosticsDrawer';
import EmptyState from '../../components/EmptyState';
import ErrorBoundary from '../../components/ErrorBoundary';
import { useToast } from '../../components/ToastProvider';
import { useNodeDetail, useRunEvents, useRunExecution } from '../../hooks/useRunExecution';
import { runsApi } from '../../api/runs';
import { tasksApi } from '../../api/tasks';
import {
  eventNameLabel,
  modelKey,
  modelLabel,
  nodeTitle,
  statusLabel
} from '../../constants/labels';
import { oneLine, shortId } from '../../utils/format';
import { usePageChrome } from '../../layout/pageChrome';
import { googleTokens } from '../../theme/googleTheme';
import { useI18n } from '../../i18n/I18nProvider';
import { translate } from '../../i18n';

/** 运行详情：流程 / 回放 / 交付 / 列表四视图 + 右侧节点抽屉。 */
export default function RunDetailPage() {
  const { t, locale } = useI18n();
  const { runId } = useParams();
  const navigate = useNavigate();
  const location = useLocation();
  const [searchParams] = useSearchParams();
  const toast = useToast();
  const queryClient = useQueryClient();
  const [tab, setTab] = useState('flow');
  const [selectedId, setSelectedId] = useState(null);
  const [selectedEdge, setSelectedEdge] = useState(null);
  const [inspectorOpen, setInspectorOpen] = useState(true);
  const [busyAction, setBusyAction] = useState(false);
  const [diagnosticsOpen, setDiagnosticsOpen] = useState(false);
  const [glassboxRequest, setGlassboxRequest] = useState(null);
  const consumedQueryRequest = useRef('');
  const inlineInspector = useMediaQuery('(min-width:1024px)');
  const requestedNodeId = searchParams.get('nodeId');
  const requestedGlassbox = searchParams.get('glassbox');

  const { detail, view, live, streamConnected, loading, error, refetch } = useRunExecution(runId);
  const eventsQuery = useRunEvents(runId, tab === 'replay', live && !streamConnected);
  const selectedInView = useMemo(
    () =>
      Boolean(
        selectedId &&
        view?.runId === runId &&
        (view.nodes ?? []).some(item => item.id === selectedId)
      ),
    [runId, selectedId, view?.runId, view?.nodes]
  );
  const nodeDetail = useNodeDetail(
    runId,
    selectedInView ? selectedId : null,
    live && !streamConnected
  );
  const node = useMemo(
    () => (view?.nodes ?? []).find(item => item.id === selectedId) ?? null,
    [view?.nodes, selectedId]
  );

  useEffect(() => {
    setSelectedId(null);
    setSelectedEdge(null);
    setInspectorOpen(false);
    setTab('flow');
    setGlassboxRequest(null);
    consumedQueryRequest.current = '';
  }, [runId]);

  useEffect(() => {
    if (!view || selectedId || requestedNodeId) return;
    const active = view.focus?.active?.[0]?.nodeId;
    const running = view.nodes?.find(item => item.status === 'RUNNING')?.id;
    const next = active || running || null;
    if (next) {
      setSelectedId(next);
      setInspectorOpen(true);
    }
  }, [view, selectedId, requestedNodeId]);

  // 支持诊断向导与慢节点排行通过 URL 直接定位节点并打开指定玻璃盒 Tab。
  useEffect(() => {
    if (!view || !requestedNodeId) return;
    const requestKey = `${location.key}|${requestedNodeId}|${requestedGlassbox || ''}`;
    if (consumedQueryRequest.current === requestKey) return;
    const target = (view.nodes ?? []).find(item => item.id === requestedNodeId);
    if (!target) return;
    consumedQueryRequest.current = requestKey;
    const allowed = ['result', 'handoff', 'gateway', 'llm', 'assembly'];
    setSelectedId(target.id);
    setSelectedEdge(null);
    setInspectorOpen(true);
    setTab('flow');
    setGlassboxRequest({
      nodeId: target.id,
      section: allowed.includes(requestedGlassbox) ? requestedGlassbox : 'result',
      nonce: Date.now()
    });
  }, [view, requestedNodeId, requestedGlassbox, location.key]);

  const goal = detail?.task?.GOAL || t('run.title');
  const goalTitle = oneLine(String(goal).split(/[。:：]/)[0], 18);
  const taskModel = detail?.task?.MODEL_MODE;
  const assemblyModels = (detail?.agentAssemblies ?? [])
    .map(record => record.snapshot?.components?.model?.mode)
    .filter(Boolean)
    .map(mode => modelKey(mode));
  const modelModes = new Set(
    [taskModel, ...assemblyModels].filter(Boolean).map(mode => modelKey(mode))
  );
  const runModel = modelModes.size > 1 ? 'mixed' : ([...modelModes][0] ?? null);
  usePageChrome({
    trail: [
      { label: t('nav.workspace') },
      { label: goalTitle },
      { label: t('common.runId', { id: shortId(runId) }), mono: true }
    ],
    status: detail?.task?.TASK_STATUS ?? view?.runStatus ?? detail?.run?.STATUS ?? null,
    model: {
      label:
        runModel === 'mixed'
          ? t('common.mixedModel')
          : runModel
            ? modelLabel(runModel, undefined, locale)
            : t('common.unknownModel'),
      value: runModel,
      readOnly: true
    }
  });

  const invalidate = () => {
    queryClient.invalidateQueries({ queryKey: ['run', runId] });
    queryClient.invalidateQueries({ queryKey: ['execution-view', runId] });
    queryClient.invalidateQueries({ queryKey: ['node-detail', runId] });
    queryClient.invalidateQueries({ queryKey: ['delivery', runId] });
    queryClient.invalidateQueries({ queryKey: ['tasks'] });
    queryClient.invalidateQueries({ queryKey: ['run-events', runId] });
  };

  const handleAction = async action => {
    if (!action?.enabled) return;
    setBusyAction(true);
    try {
      switch (action.actionCode) {
        case 'APPROVE_PLAN':
          await runsApi.approvePlan(runId, action.planVersionId);
          toast.success(t('run.confirmedPlan'));
          break;
        case 'PAUSE':
          await runsApi.pause(runId);
          toast.info(t('run.pauseRequested'));
          break;
        case 'RESUME':
          await runsApi.resume(runId);
          toast.info(t('run.resumeRequested'));
          break;
        case 'CANCEL':
          await runsApi.cancel(runId);
          toast.info(t('run.cancelRequested'));
          break;
        case 'TAKE_OVER':
          await runsApi.takeHandover(runId, '用户在暂停后人工接手');
          toast.success(t('run.takeOver'));
          break;
        case 'RETURN_HANDOVER': {
          const handover = await runsApi.returnHandover(runId, '用户交还工作区');
          toast.success(
            handover.evidenceInvalidated ? t('run.returnedInvalidated') : t('run.returnedUnchanged')
          );
          break;
        }
        case 'RECOVER': {
          const recovered = await runsApi.recover(runId, action.checkpointId);
          toast.success(t('run.recovered'));
          invalidate();
          navigate(`/runs/${recovered.runId}`);
          return;
        }
        default:
          break;
      }
      invalidate();
    } catch (failure) {
      toast.error(failure.message);
    } finally {
      setBusyAction(false);
    }
  };

  const submitSupplement = async (requestId, expectedTaskVersion, path, content) => {
    try {
      await runsApi.submitSupplement(runId, requestId, {
        expectedTaskVersion,
        path,
        content
      });
      toast.success(t('run.supplementAccepted'));
      invalidate();
    } catch (failure) {
      toast.error(failure.message);
    }
  };

  const archiveTask = async () => {
    if (!detail?.task?.ID) return;
    try {
      await tasksApi.archive(detail.task.ID, !detail.task.ARCHIVED_AT);
      toast.success(detail.task.ARCHIVED_AT ? t('run.unarchived') : t('run.archived'));
      invalidate();
    } catch (failure) {
      toast.error(failure.message);
    }
  };

  if (loading && !view && !detail) {
    return (
      <Stack spacing={1.5} sx={{ height: '100%', alignItems: 'center', justifyContent: 'center' }}>
        <CircularProgress size={22} />
        <Typography variant="body2" color="text.secondary">
          {t('run.loading')}
        </Typography>
      </Stack>
    );
  }

  if (error && !view) {
    return (
      <Box sx={{ p: 4, maxWidth: 720, mx: 'auto' }}>
        <Alert
          severity="error"
          action={
            <Button color="inherit" size="small" onClick={() => refetch()}>
              {t('common.retry')}
            </Button>
          }
        >
          {t('run.loadFailed', { message: error.message })}
        </Alert>
        <Button sx={{ mt: 2 }} onClick={() => navigate('/runs')}>
          {t('run.backToList')}
        </Button>
      </Box>
    );
  }

  if (!view) {
    return (
      <EmptyState
        title={t('run.noViewTitle')}
        description={t('run.noViewDescription')}
        action={{ label: t('run.backToList'), onClick: () => navigate('/runs') }}
      />
    );
  }

  const workspaceDelivered = Boolean(detail?.task?.WORKSPACE_DELIVERED);
  const focusItems = view.focus?.active ?? [];
  const blocking = (view.blocking ?? []).filter(
    item =>
      !(view.reason && item.code === 'RUN_BLOCKED' && !item.nodeId) &&
      !(workspaceDelivered && item.code === 'RUN_BLOCKED')
  );

  const breakpoints = new Set(view.breakpoints ?? []);
  const toggleBreakpoint = async nodeId => {
    const next = new Set(breakpoints);
    if (next.has(nodeId)) next.delete(nodeId);
    else next.add(nodeId);
    try {
      await runsApi.updateDebugControl(runId, {
        executionMode: view?.executionMode || 'STEP_TEACHING',
        breakpoints: [...next]
      });
      toast.success(next.has(nodeId) ? t('run.breakpointSet') : t('run.breakpointCleared'));
      invalidate();
    } catch (err) {
      toast.error(err.message || t('run.breakpointFailed'));
    }
  };

  const inspector = (
    <ErrorBoundary scope="drawer" resetKeys={[runId, selectedId]}>
      <NodeInspector
        open={inspectorOpen}
        node={node}
        detail={nodeDetail.data}
        loading={nodeDetail.isLoading}
        error={nodeDetail.error}
        runId={runId}
        taskId={detail?.task?.ID ?? view.taskId}
        generatedAt={view.generatedAt}
        runModelMode={detail?.task?.MODEL_MODE}
        selectedEdge={selectedEdge}
        glassboxRequest={glassboxRequest}
        hasBreakpoint={Boolean(node && breakpoints.has(node.id))}
        onToggleBreakpoint={() => node && toggleBreakpoint(node.id)}
        cancelAction={(view.availableActions ?? []).find(action => action.actionCode === 'CANCEL')}
        executor={node?.agentName || t('run.executor')}
        onClose={() => setInspectorOpen(false)}
        onCancelRun={() => {
          const cancel = (view.availableActions ?? []).find(
            action => action.actionCode === 'CANCEL'
          );
          if (cancel) handleAction(cancel);
        }}
        onOpenLogs={() => {
          setTab('replay');
          setInspectorOpen(false);
        }}
      />
    </ErrorBoundary>
  );

  return (
    <Box sx={{ height: '100%', display: 'flex', flexDirection: 'column', minHeight: 0 }}>
      <RunHeader
        view={view}
        tab={tab}
        onTab={setTab}
        onAction={handleAction}
        busy={busyAction}
        workspaceId={detail?.task?.WORKSPACE_ID}
        onOpenWorkspace={() => {
          const wsId = detail?.task?.WORKSPACE_ID;
          if (wsId) navigate(`/workspaces?id=${wsId}`);
        }}
        diagnoseVisible={
          ['FAILED', 'BLOCKED', 'LIMITED', 'INTERRUPTED'].includes(view.runStatus) ||
          blocking.length > 0
        }
        onDiagnose={() => setDiagnosticsOpen(true)}
      />

      <Box sx={{ flex: 1, minHeight: 0, display: 'flex', bgcolor: googleTokens.canvas }}>
        <Box sx={{ flex: 1, minWidth: 0, minHeight: 0, position: 'relative' }}>
          {tab === 'flow' && (
            <Box sx={{ height: '100%', position: 'relative' }}>
              <ErrorBoundary scope="canvas" resetKeys={[runId, selectedId]}>
                <RunFlowGraph
                  nodes={view.nodes ?? []}
                  edges={view.edges ?? []}
                  revisionEdges={view.revisionEdges ?? []}
                  runId={runId}
                  selectedId={selectedId}
                  selectedEdgeId={
                    selectedEdge ? `${selectedEdge.fromNodeId}:${selectedEdge.toNodeId}` : null
                  }
                  focusIds={new Set(focusItems.map(item => item.nodeId))}
                  breakpoints={breakpoints}
                  generatedAt={view.generatedAt}
                  viewportKey={inspectorOpen}
                  onSelectNode={id => {
                    setSelectedId(id);
                    setSelectedEdge(null);
                    setInspectorOpen(true);
                  }}
                  onSelectEdge={edge => {
                    setSelectedId(edge.toNodeId);
                    setSelectedEdge(edge);
                    setInspectorOpen(true);
                  }}
                />
              </ErrorBoundary>

              {(focusItems.length > 0 || view.reason) && (
                <Box
                  sx={{
                    position: 'absolute',
                    top: 16,
                    left: 16,
                    right: 16,
                    maxWidth: 620,
                    zIndex: 2,
                    display: 'flex',
                    flexDirection: 'column',
                    gap: 0.75
                  }}
                >
                  {focusItems.map(item => (
                    <Box
                      key={`${item.nodeId}:${item.activityId}`}
                      sx={{
                        px: 1.5,
                        py: 0.9,
                        borderRadius: 2.5,
                        bgcolor: 'rgba(232,240,254,.94)',
                        border: '1px solid #D2E3FC',
                        color: '#1967D2',
                        fontSize: 12
                      }}
                    >
                      {t('run.currentAction')} · {item.nodeLabel || t('run.node')} ·{' '}
                      {item.activityKind}
                      {item.activityLabel ? ` · ${item.activityLabel}` : ''}
                    </Box>
                  ))}
                  {view.reason &&
                    (workspaceDelivered ? (
                      <Alert severity="success" sx={{ py: 0.25 }}>
                        {t('run.deliveredNotice', {
                          status: statusLabel(view.runStatus, locale)
                        })}
                      </Alert>
                    ) : (
                      <Alert severity="warning" sx={{ py: 0.25 }}>
                        {view.reason}
                      </Alert>
                    ))}
                </Box>
              )}

              {blocking.length > 0 && (
                <Stack
                  spacing={0.75}
                  sx={{
                    position: 'absolute',
                    bottom: 14,
                    left: 16,
                    right: 16,
                    maxWidth: 680,
                    zIndex: 2
                  }}
                >
                  {blocking.map((item, index) => (
                    <Alert
                      key={`${item.code}:${index}`}
                      severity="warning"
                      sx={{ boxShadow: '0 2px 10px rgba(60,64,67,.12)' }}
                    >
                      <Typography variant="body2" sx={{ fontWeight: 500 }}>
                        {translate(locale, `run.blocking.${item.code}`) ===
                        `run.blocking.${item.code}`
                          ? item.code
                          : t(`run.blocking.${item.code}`)}
                        {item.nodeId
                          ? ` · ${nodeTitle(
                              view.nodes?.find(n => n.id === item.nodeId),
                              '',
                              locale
                            )}`
                          : ''}
                      </Typography>
                      <Typography variant="caption">{item.message}</Typography>
                    </Alert>
                  ))}
                </Stack>
              )}
            </Box>
          )}

          {tab === 'replay' && (
            <RunReplayPanel
              view={view}
              detail={detail}
              events={eventsQuery.data ?? []}
              eventsLoading={eventsQuery.isLoading}
              eventNames={Object.fromEntries(
                [
                  'TASK_CREATED',
                  'PLAN_APPROVED',
                  'RUN_STARTED',
                  'DELEGATED',
                  'AGENT_STARTED',
                  'NODE_RETRY_SCHEDULED',
                  'MODEL_STARTED',
                  'MODEL_FINISHED',
                  'TOOL_STARTED',
                  'TOOL_FINISHED',
                  'TOOL_REJECTED',
                  'HANDOFF',
                  'HANDOFF_ACCEPTED',
                  'CHECK_STARTED',
                  'CHECK_FINISHED',
                  'CHECKPOINT_SAVED',
                  'CHECKPOINT_SKIPPED',
                  'RUN_COMPLETED',
                  'CANCEL_REQUESTED',
                  'PAUSE_REQUESTED',
                  'PAUSED',
                  'RESUMED',
                  'CANCELLED',
                  'FAILED',
                  'LIMITED',
                  'INTERRUPTED',
                  'SUPPLEMENT_REQUESTED',
                  'SUPPLEMENT_APPLIED',
                  'SUPPLEMENT_REJECTED',
                  'CONTINUED_AFTER_SUPPLEMENT',
                  'RECOVERY_STARTED',
                  'MEMORY_LOADED',
                  'TEST_STARTED',
                  'TEST_FINISHED',
                  'BLOCKED'
                ].map(code => [code, eventNameLabel(code, locale)])
              )}
              onLocate={nodeId => {
                setSelectedId(nodeId);
                setInspectorOpen(true);
                setTab('flow');
              }}
            />
          )}

          {tab === 'output' && (
            <RunOutputsPanel
              detail={detail}
              view={view}
              runId={runId}
              workspaceId={detail?.task?.WORKSPACE_ID}
            />
          )}

          {tab === 'list' && (
            <RunListView
              detail={detail}
              view={view}
              onAction={handleAction}
              onSelectNode={id => {
                setSelectedId(id);
                setInspectorOpen(true);
                setTab('flow');
              }}
              onRecover={id =>
                handleAction({ actionCode: 'RECOVER', checkpointId: id, enabled: true })
              }
              onSwitchRun={id => navigate(`/runs/${id}`)}
              onSubmitSupplement={submitSupplement}
              onArchiveTask={archiveTask}
            />
          )}
        </Box>

        {inspectorOpen && node && inlineInspector && (
          <Box
            sx={{
              width: { xs: 340, lg: 440 },
              flexShrink: 0,
              height: '100%',
              borderLeft: `1px solid ${googleTokens.outline}`,
              bgcolor: '#FFFFFF'
            }}
          >
            {inspector}
          </Box>
        )}
      </Box>

      <Drawer
        anchor="right"
        open={Boolean(inspectorOpen && node && !inlineInspector)}
        onClose={() => setInspectorOpen(false)}
        slotProps={{ paper: { sx: { width: '100%', maxWidth: 440, backgroundImage: 'none' } } }}
      >
        {inspectorOpen && node ? inspector : null}
      </Drawer>

      <RunDiagnosticsDrawer
        open={diagnosticsOpen}
        runId={runId}
        onClose={() => setDiagnosticsOpen(false)}
      />

      <ErrorBoundary scope="drawer" resetKeys={[runId]}>
        <ToolApprovalDrawer runId={runId} live={live} />
      </ErrorBoundary>
    </Box>
  );
}
