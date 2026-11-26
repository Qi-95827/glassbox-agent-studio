import { useMemo, useState } from 'react';
import { Box, Chip, Collapse, Stack, Typography } from '@mui/material';
import ExpandMoreRounded from '@mui/icons-material/ExpandMoreRounded';
import ExpandLessRounded from '@mui/icons-material/ExpandLessRounded';
import PsychologyOutlined from '@mui/icons-material/PsychologyOutlined';
import BuildOutlined from '@mui/icons-material/BuildOutlined';
import GatePipeline, { defaultGateIndex } from './GatePipeline';
import EmptyState from '../../../components/EmptyState';
import { Readout } from '../../../components/WorkbenchDialog';
import { duration } from '../../../utils/format';
import { googleTokens } from '../../../theme/googleTheme';
import { useI18n } from '../../../i18n/I18nProvider';

const timeOf = value => {
  const parsed = Date.parse(value ?? '');
  return Number.isNaN(parsed) ? Number.MAX_SAFE_INTEGER : parsed;
};

/**
 * 把一次节点尝试拆成 ReAct 轮次。
 *
 * <p>判定规则完全基于服务端记录的时间顺序：每次模型调用开启一个新 Turn，
 * 紧随其后、在下一次模型调用之前的工具调用属于该 Turn。这是 ReAct 循环的真实结构，
 * 不是按数组下标硬切——模型可能在同一 Turn 内连续调多个工具，也可能直接给出结论。
 *
 * @param attempts 节点详情中的尝试列表，每项含 models 与 tools。
 * @returns 按时间排序的轮次数组；每轮含模型调用（可能为空）与所属工具调用。
 */
export function buildReActTurns(attempts) {
  const turns = [];
  for (const attempt of attempts ?? []) {
    const items = [
      ...(attempt.models ?? []).map(call => ({ kind: 'model', call })),
      ...(attempt.tools ?? []).map(call => ({ kind: 'tool', call }))
    ].sort((left, right) => timeOf(left.call.createdAt) - timeOf(right.call.createdAt));

    let current = null;
    for (const item of items) {
      if (item.kind === 'model') {
        current = {
          number: turns.length + 1,
          attemptNumber: attempt.number ?? null,
          model: item.call,
          tools: []
        };
        turns.push(current);
        continue;
      }
      if (!current) {
        // 历史数据里可能出现「先有工具调用、没有模型记录」的尝试，单独成轮而不是丢弃。
        current = {
          number: turns.length + 1,
          attemptNumber: attempt.number ?? null,
          model: null,
          tools: []
        };
        turns.push(current);
      }
      current.tools.push(item.call);
    }
  }
  return turns;
}

function ToolCallBlock({ call }) {
  const { t } = useI18n();
  const checks = call.trace?.gatewayChecks ?? [];
  const [selectedGate, setSelectedGate] = useState(() => defaultGateIndex(checks));

  return (
    <Box
      data-testid="swimlane-tool"
      sx={{
        p: 1.25,
        borderRadius: 2.5,
        border: `1px solid ${googleTokens.outline}`,
        bgcolor: '#FFFFFF'
      }}
    >
      <Stack
        direction="row"
        spacing={0.75}
        sx={{ alignItems: 'center', flexWrap: 'wrap', mb: 0.75 }}
      >
        <BuildOutlined sx={{ fontSize: 15, color: '#5F6368' }} />
        <Typography sx={{ fontSize: 13, fontWeight: 600 }}>{call.name}</Typography>
        {call.version != null && (
          <Chip size="small" label={`v${call.version}`} sx={{ height: 19, fontSize: 10.5 }} />
        )}
        <Chip
          size="small"
          label={call.status}
          sx={{
            height: 19,
            fontSize: 10.5,
            fontWeight: 600,
            bgcolor: call.status === 'FAILED' ? googleTokens.errorBg : googleTokens.successBg,
            color: call.status === 'FAILED' ? googleTokens.errorFg : googleTokens.successFg
          }}
        />
        <Typography variant="caption" color="text.secondary">
          {duration(call.durationMs)}
        </Typography>
      </Stack>

      <GatePipeline checks={checks} selected={selectedGate} onSelect={setSelectedGate} />

      {(call.trace?.fullResult || call.result || call.errorMessage) && (
        <Box sx={{ mt: 0.9 }}>
          <Typography variant="caption" color="text.secondary" sx={{ fontWeight: 600 }}>
            {t('gateway.resultTitle')}
          </Typography>
          <Readout mono maxHeight={140}>
            {call.errorMessage || call.trace?.fullResult || call.result}
          </Readout>
        </Box>
      )}
    </Box>
  );
}

function TurnCard({ turn }) {
  const { t } = useI18n();
  const payload = turn.model?.payload;
  const reasoning = payload?.reasoningContent ?? '';
  const content = payload?.responseContent ?? '';
  const [reasoningOpen, setReasoningOpen] = useState(false);

  return (
    <Box
      data-testid="swimlane-turn"
      data-turn={turn.number}
      sx={{ display: 'flex', gap: 1.25, alignItems: 'flex-start' }}
    >
      <Stack sx={{ alignItems: 'center', pt: 0.4, width: 44, flexShrink: 0 }}>
        <Box
          sx={{
            width: 30,
            height: 30,
            borderRadius: '50%',
            display: 'grid',
            placeItems: 'center',
            bgcolor: '#E8F0FE',
            color: '#1967D2',
            fontSize: 12.5,
            fontWeight: 700
          }}
        >
          T{turn.number}
        </Box>
        <Box sx={{ flex: 1, width: 2, bgcolor: googleTokens.outline, mt: 0.5, minHeight: 12 }} />
      </Stack>

      <Stack spacing={1} sx={{ flex: 1, minWidth: 0, pb: 1.5 }}>
        <Stack direction="row" spacing={0.75} sx={{ alignItems: 'center', flexWrap: 'wrap' }}>
          <PsychologyOutlined sx={{ fontSize: 16, color: '#5F6368' }} />
          <Typography sx={{ fontSize: 13.5, fontWeight: 600 }}>
            {turn.model?.name || t('swimlane.noModel')}
          </Typography>
          {turn.attemptNumber != null && (
            <Chip
              size="small"
              label={t('glassbox.attempt', { number: turn.attemptNumber })}
              sx={{ height: 19, fontSize: 10.5 }}
            />
          )}
          {turn.model && (
            <Typography variant="caption" color="text.secondary">
              {duration(turn.model.durationMs)}
            </Typography>
          )}
          {payload?.streamed && (
            <Chip
              size="small"
              label={t('swimlane.streamed')}
              sx={{ height: 19, fontSize: 10.5, bgcolor: '#E6F4EA', color: '#137333' }}
            />
          )}
        </Stack>

        {reasoning && (
          <Box>
            <Box
              component="button"
              type="button"
              onClick={() => setReasoningOpen(value => !value)}
              data-testid="swimlane-reasoning-toggle"
              sx={{
                display: 'inline-flex',
                alignItems: 'center',
                gap: 0.4,
                border: 'none',
                background: 'none',
                p: 0,
                cursor: 'pointer',
                color: '#5F6368',
                font: 'inherit'
              }}
            >
              {reasoningOpen ? (
                <ExpandLessRounded sx={{ fontSize: 16 }} />
              ) : (
                <ExpandMoreRounded sx={{ fontSize: 16 }} />
              )}
              <Typography variant="caption" sx={{ fontWeight: 600 }}>
                {t('swimlane.reasoning', { count: reasoning.length })}
              </Typography>
            </Box>
            {/* 默认折叠且不挂载：长思考链常常上万字，折叠时保留 DOM 只会拖慢抽屉打开。 */}
            <Collapse in={reasoningOpen} unmountOnExit>
              <Box
                sx={{
                  mt: 0.5,
                  p: 1,
                  borderRadius: 2,
                  bgcolor: '#F7F4FD',
                  border: '1px dashed #D9CCF5',
                  whiteSpace: 'pre-wrap',
                  fontSize: 12,
                  lineHeight: 1.65,
                  color: '#4A3B6B',
                  maxHeight: 220,
                  overflowY: 'auto'
                }}
              >
                {reasoning}
              </Box>
            </Collapse>
          </Box>
        )}

        {(content || turn.model?.errorMessage) && (
          <Readout mono maxHeight={180}>
            {turn.model?.errorMessage || content}
          </Readout>
        )}

        {turn.tools.map(call => (
          <ToolCallBlock key={call.id} call={call} />
        ))}
      </Stack>
    </Box>
  );
}

/**
 * ReAct 实时泳道：按轮次串起「思考链 → 模型输出 → 工具调用 → 安检门 → 工具结果」。
 *
 * <p>全部内容来自服务端落库的模型调用快照与工具调用轨迹；运行中的增量片段在节点抽屉的
 * 实时区域展示，两者都不做任何本地推断。
 */
export default function ReActSwimlane({ attempts }) {
  const { t } = useI18n();
  const turns = useMemo(() => buildReActTurns(attempts), [attempts]);

  if (turns.length === 0) {
    return (
      <EmptyState
        compact
        title={t('swimlane.emptyTitle')}
        description={t('swimlane.emptyDescription')}
      />
    );
  }

  return (
    <Stack data-testid="react-swimlane" spacing={0}>
      {turns.map(turn => (
        <TurnCard key={`${turn.number}:${turn.model?.id ?? 'tool-only'}`} turn={turn} />
      ))}
    </Stack>
  );
}
