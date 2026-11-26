import { useMemo, useState } from 'react';
import {
  Alert,
  Box,
  Button,
  Chip,
  LinearProgress,
  Slider,
  Stack,
  TextField,
  Typography
} from '@mui/material';
import { useQuery } from '@tanstack/react-query';
import { runsApi } from '../../api/runs';
import { useI18n } from '../../i18n/I18nProvider';
import { googleTokens } from '../../theme/googleTheme';

/** 模块 id → 展示名；与后端 ArchitectureXrayService 的模块列表一一对应。 */
const MODULE_LABEL = {
  'run-lifecycle': 'xray.module.runLifecycle',
  'agent-assembly': 'xray.module.agentAssembly',
  'tool-gateway': 'xray.module.toolGateway',
  'handoff-envelope': 'xray.module.handoff',
  'model-byok': 'xray.module.model',
  'delivery-monitoring': 'xray.module.observability'
};

function ModuleCard({ module, t, onOpen }) {
  const active = module.status === 'ACTIVE';
  return (
    <Box
      data-testid={`xray-module-${module.id}`}
      data-status={module.status}
      sx={{
        border: '1px solid',
        borderColor: active ? googleTokens.primary : 'divider',
        borderLeft: `4px solid ${active ? googleTokens.primary : '#DADCE0'}`,
        borderRadius: 2,
        p: 1.25,
        bgcolor: active ? '#F3F8FF' : '#FFFFFF',
        flex: '1 1 240px',
        minWidth: 240
      }}
    >
      <Stack direction="row" spacing={0.75} sx={{ alignItems: 'center' }}>
        <Typography variant="subtitle2">{t(MODULE_LABEL[module.id] ?? module.id)}</Typography>
        <Chip
          size="small"
          color={active ? 'primary' : 'default'}
          label={active ? t('xray.active') : t('xray.idle')}
        />
      </Stack>
      <Typography variant="caption" color="text.secondary" data-testid="xray-calls">
        {t('xray.calls', { count: module.callCount })} ·{' '}
        {t('xray.duration', { ms: module.durationMs })}
      </Typography>
      {module.compactionSavedPercent != null && (
        <Typography variant="caption" color="text.secondary" sx={{ display: 'block' }}>
          {t('xray.compaction', { percent: module.compactionSavedPercent })}
        </Typography>
      )}
      {module.id === 'tool-gateway' && (
        <Typography
          variant="caption"
          color={module.gateBlocks > 0 ? 'error' : 'text.secondary'}
          sx={{ display: 'block' }}
          data-testid="xray-blocks"
        >
          {t('xray.blocks', { count: module.gateBlocks })}
        </Typography>
      )}
      {active && module.anchorNodes?.length > 0 && (
        <Typography
          variant="caption"
          color="primary"
          sx={{ display: 'block', mt: 0.5, cursor: 'pointer' }}
          data-testid={`xray-anchor-${module.id}`}
          onClick={() => onOpen(module.anchorNodes[0], module.id)}
        >
          {t('xray.jump', { node: module.anchorNodes[0].slice(0, 8) })}
        </Typography>
      )}
    </Box>
  );
}

/**
 * 实时架构 X 光：按一次真实运行的实际轨迹点亮模块。
 *
 * <p>面板不模拟、不预判：模块指标全部来自后端对已落库记录的统计；没被走到的模块显示为空闲。
 * 底部步进回放只裁剪「已发生的步骤」，不会改变模块的累计指标口径。
 */
export default function ArchitectureXrayPanel({ initialRunId = '', onOpenRun }) {
  const { t } = useI18n();
  const [runId, setRunId] = useState(initialRunId);
  const [loaded, setLoaded] = useState(initialRunId);
  const [step, setStep] = useState(null);

  const xray = useQuery({
    queryKey: ['architecture-xray', loaded],
    queryFn: () => runsApi.architectureXray(loaded),
    enabled: Boolean(loaded)
  });

  const data = xray.data;
  const steps = useMemo(() => data?.steps ?? [], [data]);
  const current = step == null ? steps.length : step;
  const visible = steps.slice(0, current);

  const touched = useMemo(() => {
    const set = new Set(visible.map(item => item.moduleId));
    return set;
  }, [visible]);

  const latestStep = visible[visible.length - 1] ?? null;

  const open = (nodeId, moduleId) => {
    if (!nodeId) return;
    const tab = latestStep?.glassboxTab ?? 'result';
    onOpenRun?.(loaded, nodeId, tab, moduleId);
  };

  return (
    <Box data-testid="architecture-xray">
      <Stack direction="row" spacing={1} sx={{ alignItems: 'center', mb: 1.5, flexWrap: 'wrap' }}>
        <TextField
          size="small"
          label={t('xray.runId')}
          value={runId}
          onChange={event => setRunId(event.target.value)}
          sx={{ minWidth: 320 }}
          data-testid="xray-run-input"
        />
        <Button
          variant="contained"
          size="small"
          data-testid="xray-load"
          onClick={() => {
            setLoaded(runId.trim());
            setStep(null);
          }}
          disabled={!runId.trim()}
        >
          {t('xray.load')}
        </Button>
        {data && (
          <Chip
            size="small"
            label={t('xray.summary', {
              active: data.summary.activeModules,
              modules: data.modules.length
            })}
          />
        )}
      </Stack>

      {xray.error && <Alert severity="error">{xray.error.message}</Alert>}
      {xray.isLoading && <LinearProgress />}

      {data && (
        <>
          <Stack direction="row" spacing={1} sx={{ flexWrap: 'wrap', rowGap: 1 }}>
            {data.modules.map(module => {
              const dimmed = step != null && !touched.has(module.id) && module.status === 'ACTIVE';
              return (
                <Box key={module.id} sx={{ opacity: dimmed ? 0.45 : 1, flex: '1 1 240px' }}>
                  <ModuleCard module={module} t={t} onOpen={open} />
                </Box>
              );
            })}
          </Stack>

          {steps.length > 0 && (
            <Box sx={{ mt: 2 }} data-testid="xray-replay">
              <Typography variant="caption" color="text.secondary">
                {t('xray.step', { index: current, total: steps.length })}
                {latestStep ? ` · ${latestStep.kind} · ${latestStep.label ?? ''}` : ''}
              </Typography>
              <Slider
                size="small"
                min={0}
                max={steps.length}
                value={current}
                onChange={(_, value) => setStep(value)}
                data-testid="xray-step-slider"
              />
              {latestStep?.nodeId && (
                <Button
                  size="small"
                  onClick={() => open(latestStep.nodeId, latestStep.moduleId)}
                  data-testid="xray-open-step"
                >
                  {t('xray.openStep')}
                </Button>
              )}
            </Box>
          )}

          {(data.limitations ?? []).map(item => (
            <Typography
              key={item}
              variant="caption"
              color="text.secondary"
              sx={{ display: 'block', mt: 0.5 }}
            >
              · {item}
            </Typography>
          ))}
        </>
      )}
    </Box>
  );
}
