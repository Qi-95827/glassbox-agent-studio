import {
  Box,
  Button,
  Chip,
  Divider,
  Drawer,
  LinearProgress,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableRow,
  Typography
} from '@mui/material';
import LaunchRounded from '@mui/icons-material/LaunchRounded';
import CheckCircleRounded from '@mui/icons-material/CheckCircleRounded';
import CancelRounded from '@mui/icons-material/CancelRounded';
import { googleTokens } from '../../theme/googleTheme';
import { useI18n } from '../../i18n/I18nProvider';
import { duration } from '../../utils/format';

function JsonBlock({ value, maxHeight = 220 }) {
  return (
    <Box
      component="pre"
      sx={{
        m: 0,
        p: 1.5,
        bgcolor: '#F8F9FA',
        border: `1px solid ${googleTokens.outline}`,
        borderRadius: 2,
        fontSize: 11.5,
        lineHeight: 1.55,
        whiteSpace: 'pre-wrap',
        overflowWrap: 'anywhere',
        overflow: 'auto',
        maxHeight,
        fontFamily: 'ui-monospace, SFMono-Regular, Consolas, monospace'
      }}
    >
      {typeof value === 'string' ? value : JSON.stringify(value, null, 2)}
    </Box>
  );
}

function Section({ title, children }) {
  return (
    <Box>
      <Typography sx={{ fontSize: 13, fontWeight: 600, mb: 0.8 }}>{title}</Typography>
      {children}
    </Box>
  );
}

function CheckRow({ check, detail }) {
  const { t } = useI18n();
  const denied = check?.status === 'DENY';
  const name = t('evalLab.pipeline.gate.' + check.gate);
  return (
    <Stack direction="row" spacing={0.8} sx={{ alignItems: 'flex-start', py: 0.35 }}>
      {denied ? (
        <CancelRounded sx={{ fontSize: 15, color: '#B3261E', mt: '1px' }} />
      ) : check.status === 'PASS' ? (
        <CheckCircleRounded sx={{ fontSize: 15, color: '#188038', mt: '1px' }} />
      ) : null}
      <Box>
        <Typography variant="caption" sx={{ fontWeight: 600 }}>
          {name.startsWith('evalLab.pipeline.gate.') ? check.label || check.gate : name} ·{' '}
          {check?.status}
        </Typography>
        <Typography variant="caption" color="text.secondary" sx={{ display: 'block' }}>
          {detail ?? check.detail}
        </Typography>
      </Box>
    </Stack>
  );
}

function Checks({ checks = [], caseHandoff = false }) {
  const { t } = useI18n();
  return checks.length ? (
    checks.map((check, index) => (
      <CheckRow
        key={check.gate + index}
        check={check}
        detail={
          caseHandoff && check.gate === 'ATTEMPT_IDENTITY'
            ? t('evalLab.pipeline.caseHandoffIdentity')
            : undefined
        }
      />
    ))
  ) : (
    <Typography variant="body2" color="text.secondary">
      {t('evalLab.pipeline.noChecks')}
    </Typography>
  );
}

function AnswerPair({ trace, metrics }) {
  const { t } = useI18n();
  return (
    <Stack spacing={1}>
      <Section title={t('evalLab.pipeline.modelAnswer')}>
        <JsonBlock value={trace.modelAnswer || t('common.notAvailable')} />
      </Section>
      <Section title={t('evalLab.pipeline.referenceAnswer')}>
        <JsonBlock value={trace.referenceAnswer} />
      </Section>
      {metrics.answerF1 != null && (
        <Chip
          size="small"
          sx={{ alignSelf: 'start' }}
          label={'F1 ' + Math.round(metrics.answerF1 * 100) + '%'}
        />
      )}
    </Stack>
  );
}

function DocumentList({ titles = [], supporting = [] }) {
  return (
    <Stack direction="row" gap={0.6} useFlexGap flexWrap="wrap">
      {titles.map((title, index) => (
        <Chip
          key={title + index}
          label={title}
          size="small"
          color={supporting.includes(title) ? 'success' : 'default'}
          variant="outlined"
        />
      ))}
    </Stack>
  );
}

function AgentPipeline({ category, trace, metrics }) {
  const { t } = useI18n();
  const unknown = t('common.notAvailable');
  let content;
  switch (category) {
    case 'TOOL_MCP':
      content = (
        <>
          <Section title={t('evalLab.pipeline.toolSelection')}>
            <DocumentList titles={trace.availableFunctions?.names} />
            {(trace.parsedToolCalls ?? []).map((call, index) => (
              <Box key={call.name + index} sx={{ mt: 1 }}>
                <Chip size="small" label={call.name} color={call.executed ? 'success' : 'error'} />
                <JsonBlock value={call.arguments} maxHeight={120} />
                <Checks checks={call.checks} />
              </Box>
            ))}
            {!trace.parsedToolCalls?.length && <Checks />}
          </Section>
        </>
      );
      break;
    case 'CONTEXT_COMPACTION': {
      const before = metrics.beforeTokens;
      const after = metrics.afterTokens;
      content = (
        <>
          <Chip
            size="small"
            sx={{ alignSelf: 'start' }}
            label={
              trace.compactionTier ? t('evalLab.pipeline.tier.' + trace.compactionTier) : unknown
            }
          />
          {before > 0 && after != null && (
            <Stack spacing={1}>
              <Typography variant="body2">
                {t('evalLab.pipeline.beforeTokens', { count: before })}
              </Typography>
              <LinearProgress variant="determinate" value={100} />
              <Typography variant="body2">
                {t('evalLab.pipeline.afterTokens', { count: after })}
              </Typography>
              <LinearProgress
                color="success"
                variant="determinate"
                value={Math.min(100, (after * 100) / before)}
              />
              <Typography variant="caption">
                {t('evalLab.pipeline.budget', { count: metrics.compactionBudget })}
              </Typography>
              {metrics.truncated && (
                <Typography variant="caption">{t('evalLab.pipeline.truncated')}</Typography>
              )}
            </Stack>
          )}
          <Section title={t('evalLab.pipeline.summary')}>
            <JsonBlock value={trace.summaryPreview || trace.offloadedPreview || unknown} />
          </Section>
          <Section title={t('evalLab.pipeline.segments')}>
            <JsonBlock value={trace.segmentTokens ?? unknown} />
          </Section>
          {trace.choices?.length > 0 && (
            <JsonBlock
              value={trace.choices
                .map((choice, index) => String.fromCharCode(65 + index) + '. ' + choice)
                .join('\n')}
            />
          )}
        </>
      );
      break;
    }
    case 'LONG_TERM_MEMORY':
      content = (
        <>
          {trace.candidateLeaked != null && (
            <Chip
              size="small"
              sx={{ alignSelf: 'start' }}
              color={trace.candidateLeaked ? 'error' : 'success'}
              label={t(
                trace.candidateLeaked
                  ? 'evalLab.pipeline.candidateLeaked'
                  : 'evalLab.pipeline.candidateIsolated'
              )}
            />
          )}
          <Section title={t('evalLab.pipeline.recalled', { count: trace.topK ?? 5 })}>
            {trace.recalledMemories?.length ? (
              trace.recalledMemories.map(memory => (
                <Box
                  key={memory.id}
                  sx={{ border: 1, borderColor: 'divider', borderRadius: 2, p: 1.25, mb: 0.75 }}
                >
                  <Typography variant="caption">
                    {(memory.evidenceId || memory.id) + ' · ' + Number(memory.score).toFixed(3)}
                  </Typography>
                  <Typography variant="body2">{memory.content}</Typography>
                  <Typography variant="caption" color="text.secondary">
                    {memory.reason}
                  </Typography>
                </Box>
              ))
            ) : (
              <Typography variant="body2" color="text.secondary">
                {unknown}
              </Typography>
            )}
          </Section>
        </>
      );
      break;
    case 'PLANNING_MULTI_AGENT':
      content = (
        <>
          <Section title={t('evalLab.pipeline.planner')}>
            {trace.planValidation && (
              <Chip
                size="small"
                color={trace.planValidation.valid ? 'success' : 'error'}
                label={trace.planValidation.valid ? 'PASS' : 'DENY'}
              />
            )}
            <Typography variant="body2" sx={{ my: 0.6 }}>
              {t('evalLab.pipeline.selectedTitles')}
            </Typography>
            <DocumentList titles={trace.selectedTitles} supporting={trace.supportingTitles} />
            <Typography variant="body2" sx={{ my: 0.6 }}>
              {t('evalLab.pipeline.supportingTitles')}
            </Typography>
            <DocumentList titles={trace.supportingTitles} />
            <JsonBlock
              value={
                trace.planValidation?.errors?.length
                  ? trace.planValidation.errors
                  : (trace.plannerSteps ?? unknown)
              }
            />
          </Section>
          <Section title={t('evalLab.pipeline.handoff')}>
            {trace.handoffEnvelope && (
              <Typography variant="body2">{trace.handoffEnvelope.summary}</Typography>
            )}
            <Checks
              checks={trace.handoffChecks}
              caseHandoff={trace.handoffScope === 'EVALUATION_CASE'}
            />
          </Section>
          <Section title={t('evalLab.pipeline.synthesis')}>
            <Typography variant="body2">
              {t('evalLab.pipeline.reflection', { count: metrics.reflectionRounds ?? 0 })}
            </Typography>
            {trace.reflectionSummary && <JsonBlock value={trace.reflectionSummary} />}
          </Section>
        </>
      );
      break;
    case 'SAFETY_INJECTION':
      content = (
        <>
          {trace.defenseLayer && (
            <Chip
              size="small"
              sx={{ alignSelf: 'start' }}
              color={trace.defenseLayer === 'BREACHED' ? 'error' : 'success'}
              label={t('evalLab.pipeline.defense.' + trace.defenseLayer)}
            />
          )}
          <Section title={t('evalLab.pipeline.modelDefense')}>
            <JsonBlock value={trace.modelDecision ?? unknown} />
          </Section>
          <Section title={t('evalLab.pipeline.gatewayDefense')}>
            <Checks checks={trace.gatewayChecks} />
          </Section>
          <Section title={t('evalLab.pipeline.observation')}>
            <DocumentList titles={trace.attackerTools} />
            <JsonBlock value={trace.toolResponsePreview ?? unknown} />
          </Section>
        </>
      );
      break;
    default:
      return null;
  }
  return (
    <Section title={t('evalLab.pipeline.title')}>
      <Stack spacing={1.6}>
        {trace.modelName && <Typography variant="caption">{trace.modelName}</Typography>}
        {trace.modelMode === 'mock' && (
          <Chip size="small" sx={{ alignSelf: 'start' }} label={t('evalLab.field.mock')} />
        )}
        {content}
        {trace.referenceAnswer && <AnswerPair trace={trace} metrics={metrics} />}
      </Stack>
    </Section>
  );
}

const GATEWAY_ROOTS = ['toolCalls', 'attacks'];

/** 所有会在卡片网格中展示的量化指标；未登记的键不直接裸露英文变量名。 */
export const METRIC_KEYS = {
  beforeTokens: 'evalLab.metric.beforeTokens',
  afterTokens: 'evalLab.metric.afterTokens',
  afterCompactionTokens: 'evalLab.metric.afterCompactionTokens',
  actualInputTokens: 'evalLab.metric.actualInputTokens',
  actualOutputTokens: 'evalLab.metric.actualOutputTokens',
  compactionBudget: 'evalLab.metric.compactionBudget',
  contextLimit: 'evalLab.metric.contextLimit',
  compactionEnabled: 'evalLab.metric.compactionEnabled',
  choiceCorrect: 'evalLab.metric.choiceCorrect',
  compressionSavedPercent: 'evalLab.metric.compressionSavedPercent',
  offloadedTools: 'evalLab.metric.offloadedTools',
  needleRetention: 'evalLab.metric.needleRetention',
  needleCount: 'evalLab.metric.needleCount',
  retainedCount: 'evalLab.metric.retainedCount',
  truncated: 'evalLab.metric.truncated',
  api400: 'evalLab.metric.api400',
  toolCallCount: 'evalLab.metric.toolCallCount',
  executedCount: 'evalLab.metric.executedCount',
  deniedCount: 'evalLab.metric.deniedCount',
  duplicateDenied: 'evalLab.metric.duplicateDenied',
  schemaValid: 'evalLab.metric.schemaValid',
  selectionAccuracy: 'evalLab.metric.selectionAccuracy',
  gatewayPassRate: 'evalLab.metric.gatewayPassRate',
  duplicateRate: 'evalLab.metric.duplicateRate',
  validationFirstPassRate: 'evalLab.metric.validationFirstPassRate',
  recallAtK: 'evalLab.metric.recallAtK',
  precisionAtK: 'evalLab.metric.precisionAtK',
  mrr: 'evalLab.metric.mrr',
  memoryTotalCount: 'evalLab.metric.memoryTotalCount',
  retrievedCount: 'evalLab.metric.retrievedCount',
  evidenceRecall: 'evalLab.metric.evidenceRecall',
  candidateLeaked: 'evalLab.metric.candidateLeaked',
  candidateLeakCount: 'evalLab.metric.candidateLeakCount',
  memoryEnabled: 'evalLab.metric.memoryEnabled',
  targetCount: 'evalLab.metric.targetCount',
  candidateCount: 'evalLab.metric.candidateCount',
  planValid: 'evalLab.metric.planValid',
  supportingTitleRecall: 'evalLab.metric.supportingTitleRecall',
  handoffPassRate: 'evalLab.metric.handoffPassRate',
  reflectionRounds: 'evalLab.metric.reflectionRounds',
  llmCallsCount: 'evalLab.metric.llmCallsCount',
  answerExactMatch: 'evalLab.metric.answerExactMatch',
  answerF1: 'evalLab.metric.answerF1',
  modelDefended: 'evalLab.metric.modelDefended',
  modelHijacked: 'evalLab.metric.modelHijacked',
  gatewayBlocked: 'evalLab.metric.gatewayBlocked',
  endToEndSafe: 'evalLab.metric.endToEndSafe',
  attackSuccessRate: 'evalLab.metric.attackSuccessRate',
  blockRate: 'evalLab.metric.blockRate',
  gate55CaptureRate: 'evalLab.metric.gate55CaptureRate',
  injectionDefenseRate: 'evalLab.metric.injectionDefenseRate',
  fileMatchesExpected: 'evalLab.metric.fileMatchesExpected'
};

/** 纯内部调试字段：顶部胶囊与断言表已清晰呈现，不在指标卡中重复。 */
export const HIDDEN_METRICS = new Set([
  'usageKnown',
  'costKnown',
  'codeScore',
  'codePassed',
  'assertionCount',
  'reportedInputTokens',
  'modelRequestAttempted'
]);

const BOOLEAN_METRICS = new Set([
  'compactionEnabled',
  'choiceCorrect',
  'schemaValid',
  'memoryEnabled',
  'candidateLeaked',
  'planValid',
  'modelDefended',
  'modelHijacked',
  'gatewayBlocked',
  'endToEndSafe',
  'truncated',
  'executionFailed'
]);

const INTEGER_METRICS = new Set([
  'beforeTokens',
  'afterTokens',
  'afterCompactionTokens',
  'actualInputTokens',
  'actualOutputTokens',
  'compactionBudget',
  'contextLimit',
  'offloadedTools',
  'needleCount',
  'retainedCount',
  'toolCallCount',
  'executedCount',
  'deniedCount',
  'duplicateDenied',
  'memoryTotalCount',
  'retrievedCount',
  'candidateLeakCount',
  'targetCount',
  'candidateCount',
  'reflectionRounds',
  'llmCallsCount',
  'api400'
]);

const PERCENT_METRICS = new Set([
  'compressionSavedPercent',
  'needleRetention',
  'recallAtK',
  'precisionAtK',
  'mrr',
  'blockRate',
  'gate55CaptureRate',
  'injectionDefenseRate',
  'selectionAccuracy',
  'gatewayPassRate',
  'duplicateRate',
  'validationFirstPassRate',
  'handoffPassRate',
  'fileMatchesExpected',
  'evidenceRecall',
  'supportingTitleRecall',
  'attackSuccessRate',
  'answerF1',
  'answerExactMatch'
]);

/** 断言类型双语映射；未登记类型回退到通用“其他断言”。 */
export const ASSERTION_TYPE_KEYS = {
  ANSWER_EXACT_MATCH: 'evalLab.assertion.ANSWER_EXACT_MATCH',
  ANSWER_QUALITY: 'evalLab.assertion.ANSWER_QUALITY',
  CHOICE_ACCURACY: 'evalLab.assertion.CHOICE_ACCURACY',
  CHOICE_CORRECT: 'evalLab.assertion.CHOICE_CORRECT',
  CANDIDATE_ZERO_LEAK: 'evalLab.assertion.CANDIDATE_ZERO_LEAK',
  END_TO_END_SAFE: 'evalLab.assertion.END_TO_END_SAFE',
  FILE_MATCHES_EXPECTED: 'evalLab.assertion.FILE_MATCHES_EXPECTED',
  GATEWAY_PASS_RATE: 'evalLab.assertion.GATEWAY_PASS_RATE',
  HANDOFF_PASS_RATE_MIN: 'evalLab.assertion.HANDOFF_PASS_RATE_MIN',
  JSON_SCHEMA_VALID: 'evalLab.assertion.JSON_SCHEMA_VALID',
  METRIC_EQ: 'evalLab.assertion.METRIC_EQ',
  METRIC_GTE: 'evalLab.assertion.METRIC_GTE',
  METRIC_LTE: 'evalLab.assertion.METRIC_LTE',
  PLAN_VALID: 'evalLab.assertion.PLAN_VALID',
  SUPPORTING_TITLE_RECALL: 'evalLab.assertion.SUPPORTING_TITLE_RECALL',
  TOOL_CALLED: 'evalLab.assertion.TOOL_CALLED',
  TOOL_NOT_CALLED: 'evalLab.assertion.TOOL_NOT_CALLED'
};

/** 整数千分位；比率百分比；布尔按当前语言显示是/否，禁止 true/false 与 .000。 */
export function formatMetricValue(key, value, locale, t) {
  if (typeof value === 'boolean') return t(value ? 'common.yes' : 'common.no');
  if (BOOLEAN_METRICS.has(key)) return t(value ? 'common.yes' : 'common.no');
  if (typeof value !== 'number' || Number.isNaN(value)) return '—';
  const intlLocale = locale === 'en-US' ? 'en-US' : 'zh-CN';
  if (PERCENT_METRICS.has(key)) {
    const percent = Math.round(value * 1000) / 10;
    return `${percent.toLocaleString(intlLocale, { maximumFractionDigits: 1 })}%`;
  }
  if (INTEGER_METRICS.has(key) || Number.isInteger(value)) {
    return Math.round(value).toLocaleString(intlLocale);
  }
  return value.toLocaleString(intlLocale, { maximumFractionDigits: 3 });
}

/** 指标名必须走双语词典；为空的键不进入卡片网格。 */
export function metricLabel(key, t) {
  return METRIC_KEYS[key] ? t(METRIC_KEYS[key]) : '';
}

function assertionLabel(type, t) {
  const key = ASSERTION_TYPE_KEYS[type];
  return key ? t(key) : t('evalLab.assertion.OTHER');
}

function formatAssertionValue(value, t) {
  if (value == null) return '';
  if (typeof value === 'boolean') return t(value ? 'common.yes' : 'common.no');
  if (typeof value === 'object') return JSON.stringify(value);
  return String(value);
}

/** 逐题玻璃盒穿透：题目 → 输出 → 安检门 → 压缩/记忆/规划 → 双轨裁判。 */
export default function EvalCaseDetailDrawer({ open, item, categoryLabel, onClose, onOpenRun }) {
  const { t, locale } = useI18n();
  const title = item ? (locale === 'en-US' ? item.titleEn : item.titleZh) : '';
  const trace = item?.trace ?? {};
  const judge = item?.judgeVerdict ?? {};
  const metrics = item?.metrics ?? {};
  const agent = Boolean(
    trace.measurement?.startsWith('byok_') || trace.measurement?.startsWith('fixture_')
  );
  const status = metrics.executionFailed
    ? 'evalLab.case.executionFailed'
    : item?.passed
      ? 'evalLab.case.passed'
      : 'evalLab.case.failed';
  const estimatedOnly = Boolean(metrics.usageKnown === false || trace.modelMode === 'mock');
  const calls = GATEWAY_ROOTS.flatMap(key => (Array.isArray(trace[key]) ? trace[key] : []));
  const matches = Array.isArray(trace.matches) ? trace.matches : [];
  const plans = Array.isArray(trace.plans) ? trace.plans : [];

  return (
    <Drawer
      anchor="right"
      open={open}
      onClose={onClose}
      slotProps={{
        paper: { sx: { width: '100%', maxWidth: 720, backgroundImage: 'none' } }
      }}
    >
      <Box sx={{ p: 2.5 }}>
        <Stack direction="row" spacing={1} sx={{ alignItems: 'center', mb: 0.5, flexWrap: 'wrap' }}>
          <Typography sx={{ fontSize: 17, fontWeight: 600, flex: 1 }}>{title}</Typography>
          <Chip size="small" color={item?.passed ? 'success' : 'default'} label={t(status)} />
        </Stack>
        <Typography variant="caption" color="text.secondary">
          {categoryLabel?.[item?.category] ?? item?.category} · {item?.sampleId} ·{' '}
          {t('evalLab.case.repeat', { index: item?.repeatIndex ?? 1 })} ·{' '}
          {duration(item?.durationMs)}
        </Typography>
        <Stack direction="row" spacing={1} sx={{ mt: 1.2, flexWrap: 'wrap' }}>
          {item?.linkedRunId && (
            <Button
              size="small"
              variant="outlined"
              startIcon={<LaunchRounded />}
              onClick={() => onOpenRun?.(item.linkedRunId)}
            >
              {t('evalLab.case.openRun')}
            </Button>
          )}
          <Chip
            size="small"
            variant="outlined"
            label={t('evalLab.case.score', {
              score: `${Math.round((item?.codeScore ?? 0) * 100)}%`
            })}
          />
          <Chip
            size="small"
            variant="outlined"
            label={t('evalLab.case.cost', {
              cost: metrics.costKnown === false ? '—' : Number(item?.costUsd ?? 0).toFixed(5)
            })}
          />
          <Chip
            size="small"
            variant="outlined"
            label={t('evalLab.pipeline.inputTokens', {
              count: estimatedOnly ? '—' : (item?.inputTokens ?? '—')
            })}
          />
          <Chip
            size="small"
            variant="outlined"
            label={t('evalLab.pipeline.outputTokens', {
              count: estimatedOnly ? '—' : (item?.outputTokens ?? '—')
            })}
          />
        </Stack>
      </Box>
      <Divider />
      <Stack spacing={2.2} sx={{ p: 2.5 }}>
        <Section title={t('evalLab.detail.input')}>
          <JsonBlock value={trace.prompt || t('common.notAvailable')} maxHeight={160} />
        </Section>

        {metrics.errorCode && (
          <Typography variant="body2" color="error">
            {t('evalLab.pipeline.error.' + metrics.errorCode)}
          </Typography>
        )}
        {agent && <AgentPipeline category={item?.category} trace={trace} metrics={metrics} />}

        <Section title={t('evalLab.case.rawOutput')}>
          <JsonBlock value={item?.rawOutput || t('common.notAvailable')} maxHeight={220} />
        </Section>

        <Section title={t('evalLab.case.metrics')}>
          <Box
            sx={{
              display: 'grid',
              gridTemplateColumns: { xs: '1fr 1fr', sm: '1fr 1fr 1fr' },
              gap: 1
            }}
          >
            {Object.entries(item?.metrics ?? {})
              .filter(([key]) => !HIDDEN_METRICS.has(key) && METRIC_KEYS[key])
              .filter(([, value]) => typeof value === 'number' || typeof value === 'boolean')
              .map(([key, value]) => (
                <Box
                  key={key}
                  sx={{ p: 1, border: `1px solid ${googleTokens.outline}`, borderRadius: 2 }}
                >
                  <Typography variant="caption" color="text.secondary" noWrap>
                    {metricLabel(key, t)}
                  </Typography>
                  <Typography sx={{ fontSize: 15, fontWeight: 500 }}>
                    {formatMetricValue(key, value, locale, t)}
                  </Typography>
                </Box>
              ))}
          </Box>
        </Section>

        <Section title={t('evalLab.case.assertions')}>
          <Table size="small">
            <TableHead>
              <TableRow>
                <TableCell>{t('evalLab.case.type')}</TableCell>
                <TableCell>{t('evalLab.case.expected')}</TableCell>
                <TableCell>{t('evalLab.case.actual')}</TableCell>
                <TableCell>{t('common.status')}</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {(item?.assertions ?? []).map((row, index) => (
                <TableRow key={`${row.type}-${index}`}>
                  <TableCell sx={{ fontSize: 11.5 }}>{assertionLabel(row.type, t)}</TableCell>
                  <TableCell sx={{ fontSize: 11.5 }}>
                    {formatAssertionValue(row.expected, t)}
                  </TableCell>
                  <TableCell sx={{ fontSize: 11.5, maxWidth: 180 }}>
                    {formatAssertionValue(row.actual, t)}
                  </TableCell>
                  <TableCell>
                    <Chip
                      size="small"
                      color={row.passed ? 'success' : 'error'}
                      label={t(
                        row.passed ? 'evalLab.case.passedShort' : 'evalLab.case.failedShort'
                      )}
                    />
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
          {(item?.assertions ?? []).length === 0 && (
            <Typography variant="caption" color="text.secondary">
              {t('common.notAvailable')}
            </Typography>
          )}
        </Section>

        {!agent && (
          <>
            <Section title={t('evalLab.detail.gateway')}>
              {calls.length === 0 && <JsonBlock value={t('common.notAvailable')} maxHeight={80} />}
              {calls.map((call, index) => (
                <Box key={`${call.name}-${index}`} sx={{ mb: 1 }}>
                  <Stack direction="row" spacing={0.8} sx={{ alignItems: 'center', mb: 0.3 }}>
                    <Typography variant="caption" sx={{ fontWeight: 600 }}>
                      {call.name}
                    </Typography>
                    <Chip size="small" label={call.status} variant="outlined" />
                    {call.path && (
                      <Typography variant="caption" color="text.secondary">
                        {call.path}
                      </Typography>
                    )}
                  </Stack>
                  {(call.checks ?? []).map((check, checkIndex) => (
                    <CheckRow key={`${check.gate}-${checkIndex}`} check={check} />
                  ))}
                </Box>
              ))}
            </Section>

            <Section title={t('evalLab.detail.compaction')}>
              {trace.needles ? (
                <JsonBlock
                  value={{
                    tier: item?.metrics?.compactionTier,
                    beforeTokens: item?.metrics?.beforeTokens,
                    afterTokens: item?.metrics?.afterTokens,
                    savedPercent: item?.metrics?.compressionSavedPercent,
                    needles: trace.needles,
                    retained: trace.retainedNeedles,
                    segmentTokens: trace.segmentTokens,
                    summaryPreview: trace.summaryPreview
                  }}
                />
              ) : (
                <JsonBlock value={t('common.notAvailable')} maxHeight={70} />
              )}
            </Section>

            <Section title={t('evalLab.detail.memory')}>
              {matches.length ? (
                <JsonBlock value={matches} />
              ) : (
                <JsonBlock value={t('common.notAvailable')} maxHeight={70} />
              )}
            </Section>

            <Section title={t('evalLab.detail.planning')}>
              {plans.length || trace.handoffEnvelope ? (
                <JsonBlock value={{ plans, handoffEnvelope: trace.handoffEnvelope }} />
              ) : (
                <JsonBlock value={t('common.notAvailable')} maxHeight={70} />
              )}
            </Section>
          </>
        )}

        <Section title={t('evalLab.detail.judge')}>
          {judge.mode === 'DISABLED' ? (
            <Typography variant="caption" color="text.secondary">
              {t('evalLab.case.judgeDisabled')}
            </Typography>
          ) : (
            <JsonBlock value={judge} maxHeight={260} />
          )}
        </Section>

        {trace.setup && (
          <Section title={t('evalLab.detail.setup')}>
            <JsonBlock value={trace.setup} maxHeight={220} />
          </Section>
        )}
      </Stack>
    </Drawer>
  );
}
