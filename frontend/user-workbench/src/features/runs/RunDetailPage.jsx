import { useEffect, useMemo, useState } from 'react';
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
import { useNavigate, useParams } from 'react-router-dom';
import { useQueryClient } from '@tanstack/react-query';
import RunHeader from './RunHeader';
import RunFlowGraph from '../workflows/RunFlowGraph';
import RunReplayPanel from './RunReplayPanel';
import RunOutputsPanel from './RunOutputsPanel';
import RunListView from './RunListView';
import NodeInspector from './NodeInspector';
import EmptyState from '../../components/EmptyState';
import { useToast } from '../../components/ToastProvider';
import { useNodeDetail, useRunEvents, useRunExecution } from '../../hooks/useRunExecution';
import { runsApi } from '../../api/runs';
import { tasksApi } from '../../api/tasks';
import { EVENT_NAMES, modelKey, modelLabel, nodeTitle, statusLabel } from '../../constants/labels';
import { oneLine, shortId } from '../../utils/format';
import { usePageChrome } from '../../layout/pageChrome';
import { googleTokens } from '../../theme/googleTheme';

const blockingNames = {
  PLAN_APPROVAL_REQUIRED: '等待确认计划',
  RUN_PAUSED: '运行已暂停',
  RUN_INTERRUPTED: '运行已中断',
  RUN_BLOCKED: '等待处理',
  CHECK_FAILED: '检查未通过',
  NODE_FAILED: '节点未完成',
  DEPENDENCY_NOT_STARTED: '前置步骤未结算'
};

/** 运行详情：流程 / 回放 / 交付 / 列表四视图 + 右侧节点抽屉。 */
export default function RunDetailPage() {
  const { runId } = useParams();
  const navigate = useNavigate();
  const toast = useToast();
  const queryClient = useQueryClient();
  const [tab, setTab] = useState('flow');
  const [selectedId, setSelectedId] = useState(null);
  const [selectedEdge, setSelectedEdge] = useState(null);
  const [inspectorOpen, setInspectorOpen] = useState(true);
  const [busyAction, setBusyAction] = useState(false);
  const inlineInspector = useMediaQuery('(min-width:1024px)');

  const { detail, view, live, loading, error, refetch } = useRunExecution(runId);
  const eventsQuery = useRunEvents(runId, tab === 'replay', live);
  const selectedInView = useMemo(
    () =>
      Boolean(
        selectedId &&
        view?.runId === runId &&
        (view.nodes ?? []).some(item => item.id === selectedId)
      ),
    [runId, selectedId, view?.runId, view?.nodes]
  );
  const nodeDetail = useNodeDetail(runId, selectedInView ? selectedId : null, live);
  const node = useMemo(
    () => (view?.nodes ?? []).find(item => item.id === selectedId) ?? null,
    [view?.nodes, selectedId]
  );

  useEffect(() => {
    setSelectedId(null);
    setSelectedEdge(null);
    setInspectorOpen(false);
    setTab('flow');
  }, [runId]);

  useEffect(() => {
    if (!view || selectedId) return;
    const active = view.focus?.active?.[0]?.nodeId;
    const running = view.nodes?.find(item => item.status === 'RUNNING')?.id;
    const next = active || running || null;
    if (next) {
      setSelectedId(next);
      setInspectorOpen(true);
    }
  }, [view, selectedId]);

  const goal = detail?.task?.GOAL || '运行详情';
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
      { label: '工作空间' },
      { label: goalTitle },
      { label: `运行 #${shortId(runId)}`, mono: true }
    ],
    status: detail?.task?.TASK_STATUS ?? view?.runStatus ?? detail?.run?.STATUS ?? null,
    model: {
      label: runModel === 'mixed' ? '混合模型' : runModel ? modelLabel(runModel) : '未知模型',
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
          toast.success('已确认计划');
          break;
        case 'PAUSE':
          await runsApi.pause(runId);
          toast.info('已请求暂停；在途调用可能继续');
          break;
        case 'RESUME':
          await runsApi.resume(runId);
          toast.info('已请求继续');
          break;
        case 'CANCEL':
          await runsApi.cancel(runId);
          toast.info('已请求取消；在途调用可能继续');
          break;
        case 'TAKE_OVER':
          await runsApi.takeHandover(runId, '用户在暂停后人工接手');
          toast.success('已进入人工接手；修改完成后请交还');
          break;
        case 'RETURN_HANDOVER': {
          const handover = await runsApi.returnHandover(runId, '用户交还工作区');
          toast.success(
            handover.evidenceInvalidated
              ? '已交还；代码变化，旧测试与审批失效'
              : '已交还，代码版本未变化'
          );
          break;
        }
        case 'RECOVER': {
          const recovered = await runsApi.recover(runId, action.checkpointId);
          toast.success('已创建恢复运行');
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
      toast.success('补充已接受；运行将从安全边界继续');
      invalidate();
    } catch (failure) {
      toast.error(failure.message);
    }
  };

  const archiveTask = async () => {
    if (!detail?.task?.ID) return;
    try {
      await tasksApi.archive(detail.task.ID, !detail.task.ARCHIVED_AT);
      toast.success(detail.task.ARCHIVED_AT ? '已取消归档' : '任务已归档，运行和证据保留');
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
          正在读取运行快照…
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
              重试
            </Button>
          }
        >
          运行读取失败：{error.message}
        </Alert>
        <Button sx={{ mt: 2 }} onClick={() => navigate('/runs')}>
          返回运行列表
        </Button>
      </Box>
    );
  }

  if (!view) {
    return (
      <EmptyState
        title="此运行没有执行视图"
        description="历史运行可能只保留了基础记录，无法重建流程图。"
        action={{ label: '返回运行列表', onClick: () => navigate('/runs') }}
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
      toast.success(next.has(nodeId) ? '已在该节点设置断点' : '已取消节点断点');
      invalidate();
    } catch (err) {
      toast.error(err.message || '无法更新断点设置');
    }
  };

  const inspector = (
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
      hasBreakpoint={Boolean(node && breakpoints.has(node.id))}
      onToggleBreakpoint={() => node && toggleBreakpoint(node.id)}
      cancelAction={(view.availableActions ?? []).find(action => action.actionCode === 'CANCEL')}
      executor={node?.agentName || 'AutoDev-Agent v2'}
      onClose={() => setInspectorOpen(false)}
      onCancelRun={() => {
        const cancel = (view.availableActions ?? []).find(action => action.actionCode === 'CANCEL');
        if (cancel) handleAction(cancel);
      }}
      onOpenLogs={() => {
        setTab('replay');
        setInspectorOpen(false);
      }}
    />
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
      />

      <Box sx={{ flex: 1, minHeight: 0, display: 'flex', bgcolor: googleTokens.canvas }}>
        <Box sx={{ flex: 1, minWidth: 0, minHeight: 0, position: 'relative' }}>
          {tab === 'flow' && (
            <Box sx={{ height: '100%', position: 'relative' }}>
              <RunFlowGraph
                nodes={view.nodes ?? []}
                edges={view.edges ?? []}
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
                      当前动作 · {item.nodeLabel || '节点'} · {item.activityKind}
                      {item.activityLabel ? ` · ${item.activityLabel}` : ''}
                    </Box>
                  ))}
                  {view.reason &&
                    (workspaceDelivered ? (
                      <Alert severity="success" sx={{ py: 0.25 }}>
                        工作区已完成 Git 交付（Agent 执行终态：{statusLabel(view.runStatus)}）
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
                        {blockingNames[item.code] || item.code}
                        {item.nodeId
                          ? ` · ${nodeTitle(
                              view.nodes?.find(n => n.id === item.nodeId),
                              ''
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
              eventNames={EVENT_NAMES}
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
    </Box>
  );
}
