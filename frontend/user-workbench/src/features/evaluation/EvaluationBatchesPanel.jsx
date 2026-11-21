import { useEffect, useState } from 'react';
import { Alert, Box, Button, Chip, MenuItem, Stack, TextField, Typography } from '@mui/material';
import PlayArrowRounded from '@mui/icons-material/PlayArrowRounded';
import { useQueryClient } from '@tanstack/react-query';
import StatusChip from '../../components/StatusChip';
import EmptyState from '../../components/EmptyState';
import { Readout, TraceSection } from '../../components/WorkbenchDialog';
import WorkbenchDialog from '../../components/WorkbenchDialog';
import { useEvaluationDetail, useEvaluations } from '../../hooks/useEvaluations';
import { evaluationApi } from '../../api/evaluations';
import { useToast } from '../../components/ToastProvider';
import { COMPARISON_NAMES, EVALUATION_SUITES, RUN_STATES } from '../../constants/labels';
import { dateTime, duration, shortId } from '../../utils/format';
import { googleTokens } from '../../theme/googleTheme';

const emptyConfig = {
  suite: 'LOCAL_AGENT_V2',
  version: '',
  maxRounds: '',
  maxTools: '',
  maxSeconds: ''
};

/** 种子评测：启动、逐项断言、停止与历史批次对照。 */
export default function EvaluationBatchesPanel({ onOpenRun }) {
  const toast = useToast();
  const queryClient = useQueryClient();
  const evaluations = useEvaluations();
  const [selectedId, setSelectedId] = useState('');
  const [startOpen, setStartOpen] = useState(false);
  const [config, setConfig] = useState(emptyConfig);
  const [baseline, setBaseline] = useState('');
  const [comparison, setComparison] = useState(null);
  const [busy, setBusy] = useState(false);

  const list = evaluations.data ?? [];
  const activeId = selectedId || list[0]?.id || '';
  const detail = useEvaluationDetail(activeId);
  const batch = detail.data?.batch;
  const cases = detail.data?.cases ?? [];

  useEffect(() => {
    setComparison(null);
    setBaseline('');
  }, [activeId]);

  const start = async () => {
    setBusy(true);
    try {
      const created = await evaluationApi.startConfigured({
        suite: config.suite,
        version: config.version === '' ? null : Number(config.version),
        maxRounds: config.maxRounds === '' ? null : Number(config.maxRounds),
        maxTools: config.maxTools === '' ? null : Number(config.maxTools),
        maxSeconds: config.maxSeconds === '' ? null : Number(config.maxSeconds),
        requestedBy: 'local-user'
      });
      setSelectedId(created.id);
      setComparison(null);
      setStartOpen(false);
      queryClient.invalidateQueries({ queryKey: ['evaluations'] });
      toast.success('已启动模拟评测');
    } catch (error) {
      toast.error(error.message);
    } finally {
      setBusy(false);
    }
  };

  const cancel = async () => {
    try {
      await evaluationApi.cancel(activeId);
      queryClient.invalidateQueries({ queryKey: ['evaluation', activeId] });
      toast.info('已请求停止评测');
    } catch (error) {
      toast.error(error.message);
    }
  };

  const compare = async () => {
    if (!baseline || !activeId || baseline === activeId) {
      toast.warning('请选择两个不同的评测批次');
      return;
    }
    try {
      setComparison(await evaluationApi.compare(baseline, activeId));
    } catch (error) {
      toast.error(error.message);
    }
  };

  return (
    <Stack spacing={2}>
      <Stack
        direction="row"
        sx={{ justifyContent: 'space-between', alignItems: 'center', flexWrap: 'wrap', gap: 1 }}
      >
        <Typography variant="body2" color="text.secondary">
          使用模拟模型和工具检查固定流程，不访问真实模型、仓库或外部服务。
        </Typography>
        <Button
          variant="contained"
          startIcon={<PlayArrowRounded />}
          onClick={() => setStartOpen(true)}
        >
          运行种子集
        </Button>
      </Stack>

      <Box
        sx={{
          display: 'grid',
          gridTemplateColumns: { xs: '1fr', lg: '290px minmax(0, 1fr)' },
          gap: 2,
          alignItems: 'start'
        }}
      >
        <Box
          sx={{
            bgcolor: '#FFFFFF',
            border: `1px solid ${googleTokens.outline}`,
            borderRadius: 4,
            p: 1,
            maxHeight: 640,
            overflowY: 'auto'
          }}
        >
          {list.map(row => {
            const active = row.id === activeId;
            return (
              <Box
                key={row.id}
                role="button"
                tabIndex={0}
                onClick={() => setSelectedId(row.id)}
                onKeyDown={event => event.key === 'Enter' && setSelectedId(row.id)}
                sx={{
                  px: 1.25,
                  py: 1,
                  borderRadius: 2.5,
                  cursor: 'pointer',
                  bgcolor: active ? '#E8F0FE' : 'transparent',
                  '&:hover': { bgcolor: active ? '#E8F0FE' : '#F8F9FA' }
                }}
              >
                <Stack
                  direction="row"
                  spacing={0.75}
                  sx={{ alignItems: 'center', flexWrap: 'wrap' }}
                >
                  <StatusChip value={row.status} />
                  <Typography variant="caption" color="text.secondary">
                    {dateTime(row.createdAt)}
                  </Typography>
                </Stack>
                <Typography sx={{ fontSize: 13, fontWeight: active ? 600 : 500, mt: 0.5 }}>
                  {EVALUATION_SUITES[row.suite] || row.suite}
                </Typography>
                <Typography
                  variant="caption"
                  color="text.secondary"
                  sx={{ fontFamily: googleTokens.mono }}
                >
                  #{shortId(row.id)}
                </Typography>
              </Box>
            );
          })}
          {!evaluations.isLoading && list.length === 0 && (
            <EmptyState
              compact
              title="还没有评测批次"
              description="运行种子集后会在这里保留批次与逐项断言。"
            />
          )}
        </Box>

        <Box
          sx={{
            bgcolor: '#FFFFFF',
            border: `1px solid ${googleTokens.outline}`,
            borderRadius: 4,
            p: { xs: 2, sm: 2.5 },
            minWidth: 0
          }}
        >
          {!batch ? (
            <EmptyState
              title="选择一个评测批次"
              description="批次详情包含通过、失败与未完成案例；未完成不计通过。"
            />
          ) : (
            <Stack spacing={1.6}>
              <Stack
                direction="row"
                sx={{ justifyContent: 'space-between', alignItems: 'flex-start', gap: 1 }}
              >
                <Box>
                  <Stack
                    direction="row"
                    spacing={0.75}
                    sx={{ alignItems: 'center', flexWrap: 'wrap' }}
                  >
                    <Typography sx={{ fontSize: 16, fontWeight: 500 }}>
                      {EVALUATION_SUITES[batch.suite] || batch.suite}
                    </Typography>
                    <StatusChip value={batch.status} />
                  </Stack>
                  <Typography variant="caption" color="text.secondary">
                    {dateTime(batch.createdAt)} · #{shortId(batch.id)} · 定义摘要{' '}
                    {batch.definitionSha256?.slice(0, 16) || '历史记录未知'}
                  </Typography>
                </Box>
                {['RUNNING', 'CANCELLING'].includes(batch.status) && (
                  <Button size="small" color="error" variant="outlined" onClick={cancel}>
                    停止评测
                  </Button>
                )}
              </Stack>
              <Typography variant="body2" color="text.secondary">
                通过 {cases.filter(item => item.status === 'PASSED').length} / {cases.length}；
                未完成或中断不计通过。
              </Typography>
              {batch.definitionJson && (
                <TraceSection title="评测定义快照" dense>
                  <Readout mono maxHeight={180}>
                    {batch.definitionJson}
                  </Readout>
                </TraceSection>
              )}

              <Stack spacing={1.2}>
                {cases.map(item => (
                  <Box
                    key={item.id}
                    sx={{ p: 1.5, border: `1px solid ${googleTokens.outline}`, borderRadius: 3 }}
                  >
                    <Stack
                      direction="row"
                      sx={{ justifyContent: 'space-between', gap: 1, alignItems: 'flex-start' }}
                    >
                      <Box sx={{ minWidth: 0 }}>
                        <Typography variant="body2" sx={{ fontWeight: 600 }}>
                          {item.title}
                        </Typography>
                        <Typography variant="caption" color="text.secondary">
                          {item.explanation || '等待评测结果'}
                        </Typography>
                      </Box>
                      <StatusChip value={item.status} />
                    </Stack>
                    {item.checks?.length > 0 ? (
                      <TraceSection title={`逐项断言（${item.checks.length}）`}>
                        <Stack spacing={0.5}>
                          {item.checks.map(check => (
                            <Typography key={check.code} variant="caption" color="text.secondary">
                              {check.passed ? '通过' : '未通过'} · {check.detail}
                            </Typography>
                          ))}
                        </Stack>
                      </TraceSection>
                    ) : (
                      <Typography
                        variant="caption"
                        color="text.secondary"
                        sx={{ display: 'block', mt: 0.5 }}
                      >
                        逐项断言尚未记录。
                      </Typography>
                    )}
                    {item.runId && (
                      <Button size="small" sx={{ mt: 0.5 }} onClick={() => onOpenRun(item.runId)}>
                        查看运行 #{shortId(item.runId)}
                      </Button>
                    )}
                  </Box>
                ))}
                {cases.length === 0 && (
                  <Typography variant="body2" color="text.secondary">
                    批次尚无案例记录。
                  </Typography>
                )}
              </Stack>

              <Box>
                <Typography sx={{ fontSize: 13, fontWeight: 600, mb: 0.75 }}>
                  历史批次对照
                </Typography>
                <Typography variant="caption" color="text.secondary">
                  当前选中批次作为候选。评测定义一致也不能证明代码和环境一致；对照不会分析差异原因。
                </Typography>
                <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1} sx={{ mt: 1 }}>
                  <TextField
                    select
                    size="small"
                    label="基线批次"
                    value={baseline}
                    onChange={event => {
                      setBaseline(event.target.value);
                      setComparison(null);
                    }}
                    sx={{ flex: 1, minWidth: 220 }}
                  >
                    {list
                      .filter(row => row.id !== activeId)
                      .map(row => (
                        <MenuItem key={row.id} value={row.id}>
                          {EVALUATION_SUITES[row.suite] || row.suite} · {dateTime(row.createdAt)} ·{' '}
                          {RUN_STATES[row.status] || row.status}
                        </MenuItem>
                      ))}
                  </TextField>
                  <Button variant="outlined" onClick={compare} disabled={!baseline}>
                    对照
                  </Button>
                </Stack>
              </Box>

              {comparison && (
                <Stack spacing={1}>
                  <Alert severity={comparison.comparableDefinition ? 'info' : 'warning'}>
                    {comparison.comparabilityReason ||
                      (comparison.comparableDefinition
                        ? '套件编码及评测定义快照一致'
                        : '评测定义不同、缺失或无效：所有案例均无法判断')}
                  </Alert>
                  {(comparison.cases ?? []).map(item => (
                    <Box
                      key={item.code}
                      sx={{ borderBottom: '1px solid', borderColor: 'divider', pb: 1 }}
                    >
                      <Typography variant="body2" sx={{ fontWeight: 600 }}>
                        {item.title} · {COMPARISON_NAMES[item.outcome] || item.outcome}
                      </Typography>
                      <Typography variant="caption" color="text.secondary">
                        基线：
                        {RUN_STATES[item.baseline?.status] || item.baseline?.status || '无记录'} →
                        候选：
                        {RUN_STATES[item.candidate?.status] || item.candidate?.status || '无记录'}
                      </Typography>
                      <Stack direction="row" spacing={0.75} sx={{ mt: 0.5 }}>
                        {item.baseline?.runId && (
                          <Button size="small" onClick={() => onOpenRun(item.baseline.runId)}>
                            基线运行
                          </Button>
                        )}
                        {item.candidate?.runId && (
                          <Button size="small" onClick={() => onOpenRun(item.candidate.runId)}>
                            候选运行
                          </Button>
                        )}
                      </Stack>
                    </Box>
                  ))}
                </Stack>
              )}

              <Chip
                size="small"
                label={`耗时 ${duration(batch.durationMs)}`}
                sx={{ alignSelf: 'flex-start', bgcolor: '#F1F3F4' }}
              />
            </Stack>
          )}
        </Box>
      </Box>

      <WorkbenchDialog
        open={startOpen}
        onCancel={() => setStartOpen(false)}
        onOk={start}
        confirmLoading={busy}
        okText="运行种子集"
        width={620}
        title="运行本地种子评测"
        subtitle="使用模拟模型与工具，不访问真实仓库或外部服务。"
      >
        <Stack spacing={1.5} sx={{ pt: 0.5 }}>
          <Alert severity="info">批次完成不代表所有样本通过；逐项断言会保留在批次详情中。</Alert>
          <TextField
            select
            size="small"
            label="评测种子集"
            value={config.suite}
            onChange={event => setConfig({ ...config, suite: event.target.value })}
          >
            {Object.entries(EVALUATION_SUITES).map(([value, label]) => (
              <MenuItem key={value} value={value}>
                {label}
              </MenuItem>
            ))}
          </TextField>
          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1.25}>
            <TextField
              size="small"
              type="number"
              label="套件版本（可选）"
              value={config.version}
              onChange={event => setConfig({ ...config, version: event.target.value })}
              sx={{ flex: 1 }}
            />

            <TextField
              size="small"
              type="number"
              label="模型轮次上限"
              value={config.maxRounds}
              onChange={event => setConfig({ ...config, maxRounds: event.target.value })}
              sx={{ flex: 1 }}
            />
          </Stack>
          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1.25}>
            <TextField
              size="small"
              type="number"
              label="工具上限"
              value={config.maxTools}
              onChange={event => setConfig({ ...config, maxTools: event.target.value })}
              sx={{ flex: 1 }}
            />

            <TextField
              size="small"
              type="number"
              label="时间上限（秒）"
              value={config.maxSeconds}
              onChange={event => setConfig({ ...config, maxSeconds: event.target.value })}
              sx={{ flex: 1 }}
            />
          </Stack>
        </Stack>
      </WorkbenchDialog>
    </Stack>
  );
}
