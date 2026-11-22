import { useState } from 'react';
import { Box, Button, Chip, Collapse, Stack, Typography } from '@mui/material';
import CheckCircleRounded from '@mui/icons-material/CheckCircleRounded';
import CancelRounded from '@mui/icons-material/CancelRounded';
import RemoveCircleOutlineRounded from '@mui/icons-material/RemoveCircleOutlineRounded';
import InsertDriveFileOutlined from '@mui/icons-material/InsertDriveFileOutlined';
import HelpOutlineRounded from '@mui/icons-material/HelpOutlineRounded';
import { TraceSection, Readout } from '../../../components/WorkbenchDialog';
import EmptyState from '../../../components/EmptyState';
import MarkdownView from '../../../components/MarkdownView';
import { TOOL_NAMES } from '../../../constants/labels';
import { clock, shortId } from '../../../utils/format';
import { googleTokens } from '../../../theme/googleTheme';
import { HANDOFF_CHECK_RULES, describeHandoffCheckEvidence } from './checkExplanations';
import { cleanBulletMarkdown, readableHandoffSummary } from './textClean';

function checkTone(status) {
  if (status === 'PASS') {
    return {
      bg: googleTokens.successBg,
      fg: googleTokens.successFg,
      border: '#CEEAD6',
      icon: <CheckCircleRounded sx={{ fontSize: 15, color: googleTokens.successFg }} />
    };
  }
  if (status === 'DENY') {
    return {
      bg: googleTokens.errorBg,
      fg: googleTokens.errorFg,
      border: '#F2B8B5',
      icon: <CancelRounded sx={{ fontSize: 15, color: googleTokens.errorFg }} />
    };
  }
  return {
    bg: googleTokens.neutralBg,
    fg: googleTokens.neutralFg,
    border: googleTokens.outline,
    icon: <RemoveCircleOutlineRounded sx={{ fontSize: 15, color: googleTokens.neutralFg }} />
  };
}

function EnvelopeCard({ record, direction, defaultExpandBody = false }) {
  const [showRules, setShowRules] = useState(false);
  const sender = record.senderNodeLabel || record.dependencyNodeLabel || '来源节点';
  const receiver = record.receiverNodeLabel || '接收节点';
  const structured = record.structured;
  const summaryText = structured
    ? readableHandoffSummary(structured.summary, record.sourceResult, 220)
    : '';

  return (
    <Box
      sx={{
        p: 2,
        borderRadius: 3,
        bgcolor: '#FFFFFF',
        border: `1px solid ${googleTokens.outline}`
      }}
    >
      <Stack
        direction="row"
        spacing={0.75}
        sx={{ alignItems: 'center', justifyContent: 'space-between', flexWrap: 'wrap', mb: 0.75 }}
      >
        <Typography variant="subtitle2" sx={{ fontSize: 14.5 }}>
          {direction === 'outbound' ? '发往' : '收到'} · {sender} → {receiver}
        </Typography>
        <Chip
          size="small"
          label={clock(record.acceptedAt) || '已记录'}
          sx={{ bgcolor: googleTokens.successBg, color: googleTokens.successFg }}
        />
      </Stack>

      <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mb: 1.25 }}>
        发送实例 #{shortId(record.senderAgentId)}（尝试 #{shortId(record.senderAttemptId)}）→
        接收实例 #{shortId(record.receiverAgentId)}（尝试 #{shortId(record.receiverAttemptId)}）
      </Typography>

      {structured && (
        <Stack spacing={1.25} sx={{ mb: 1.5 }}>
          <Box
            sx={{
              p: 1.25,
              borderRadius: 2,
              bgcolor: '#F8F9FA',
              border: `1px solid ${googleTokens.outline}`
            }}
          >
            <Typography
              variant="caption"
              color="text.secondary"
              sx={{ fontWeight: 600, display: 'block', mb: 0.35 }}
            >
              上游核心结论
            </Typography>
            <Typography variant="body2" sx={{ lineHeight: 1.65 }}>
              {summaryText}
            </Typography>
          </Box>

          {(structured.artifacts ?? []).length > 0 && (
            <Box>
              <Typography variant="caption" color="text.secondary" sx={{ fontWeight: 600 }}>
                涉及文件 / 产物
              </Typography>
              <Stack direction="row" spacing={0.6} sx={{ flexWrap: 'wrap', rowGap: 0.6, mt: 0.5 }}>
                {structured.artifacts.map(file => (
                  <Chip
                    key={file}
                    size="small"
                    icon={<InsertDriveFileOutlined sx={{ fontSize: 14 }} />}
                    label={file}
                    sx={{ fontFamily: googleTokens.mono, fontSize: 11.5 }}
                  />
                ))}
              </Stack>
            </Box>
          )}

          {(structured.decisions ?? []).length > 0 && (
            <Box>
              <Typography
                variant="caption"
                color="text.secondary"
                sx={{ fontWeight: 600, display: 'block', mb: 0.4 }}
              >
                关键决策
              </Typography>
              <Stack spacing={0.45}>
                {structured.decisions.map((item, idx) => (
                  <Box
                    key={`dec:${idx}`}
                    sx={{
                      pl: 1.1,
                      borderLeft: `2px solid ${googleTokens.primarySoft}`
                    }}
                  >
                    <MarkdownView text={cleanBulletMarkdown(item)} dense />
                  </Box>
                ))}
              </Stack>
            </Box>
          )}

          {(structured.openRisks ?? []).length > 0 && (
            <Box
              sx={{
                p: 1.25,
                borderRadius: 2,
                bgcolor: googleTokens.warningBg,
                border: '1px solid #FDE293'
              }}
            >
              <Typography
                variant="caption"
                sx={{ fontWeight: 600, color: googleTokens.warningFg, display: 'block', mb: 0.45 }}
              >
                遗留风险 / 待确认
              </Typography>
              <Stack spacing={0.5}>
                {structured.openRisks.map((item, idx) => (
                  <Box key={`risk:${idx}`} sx={{ color: googleTokens.warningFg }}>
                    <MarkdownView text={cleanBulletMarkdown(item)} dense />
                  </Box>
                ))}
              </Stack>
            </Box>
          )}

          {(structured.validationChecks ?? []).length > 0 && (
            <Box>
              <Stack
                direction="row"
                sx={{ alignItems: 'center', justifyContent: 'space-between', mb: 0.6 }}
              >
                <Typography variant="caption" color="text.secondary" sx={{ fontWeight: 600 }}>
                  平台交接校验清单（为什么 PASS？）
                </Typography>
                <Button
                  size="small"
                  startIcon={<HelpOutlineRounded sx={{ fontSize: 14 }} />}
                  onClick={() => setShowRules(prev => !prev)}
                  sx={{ py: 0.15, px: 0.75, fontSize: 12, minHeight: 26 }}
                >
                  {showRules ? '收起判定标准' : '查看怎么算 PASS'}
                </Button>
              </Stack>

              <Stack spacing={0.75}>
                {structured.validationChecks.map((chk, idx) => {
                  const tone = checkTone(chk.status);
                  const rule = HANDOFF_CHECK_RULES[chk.gate];
                  const evidence = describeHandoffCheckEvidence(chk, record);
                  return (
                    <Box
                      key={`hc:${chk.gate || idx}`}
                      sx={{
                        p: 1.1,
                        borderRadius: 2,
                        bgcolor: '#FAFAFA',
                        border: `1px solid ${tone.border}`
                      }}
                    >
                      <Stack
                        direction="row"
                        spacing={0.75}
                        sx={{ alignItems: 'center', flexWrap: 'wrap', rowGap: 0.5 }}
                      >
                        <Chip
                          size="small"
                          icon={tone.icon}
                          label={`${chk.label} · ${chk.status}`}
                          sx={{ bgcolor: tone.bg, color: tone.fg, fontWeight: 600 }}
                        />
                        <Typography
                          variant="caption"
                          sx={{ color: googleTokens.text, lineHeight: 1.5, flex: 1, minWidth: 200 }}
                        >
                          {evidence}
                        </Typography>
                      </Stack>

                      {rule && (
                        <Collapse in={showRules}>
                          <Box
                            sx={{
                              mt: 0.8,
                              pt: 0.75,
                              borderTop: `1px dashed ${googleTokens.outline}`,
                              color: googleTokens.textSecondary
                            }}
                          >
                            <Typography
                              variant="caption"
                              sx={{ display: 'block', lineHeight: 1.55 }}
                            >
                              <strong>检查目的：</strong>
                              {rule.purpose}
                            </Typography>
                            <Typography
                              variant="caption"
                              sx={{ display: 'block', lineHeight: 1.55 }}
                            >
                              <strong>怎么算 PASS：</strong>
                              {rule.passRule}
                            </Typography>
                            <Typography
                              variant="caption"
                              sx={{ display: 'block', lineHeight: 1.55 }}
                            >
                              <strong>何时拦截 / 跳过：</strong>
                              {rule.failRule}
                            </Typography>
                          </Box>
                        </Collapse>
                      )}
                    </Box>
                  );
                })}
              </Stack>
            </Box>
          )}
        </Stack>
      )}

      {(record.toolEvidence ?? []).length > 0 && (
        <Box sx={{ mb: 1.25 }}>
          <Typography variant="caption" color="text.secondary" sx={{ fontWeight: 600 }}>
            随信封附带的工具证据（{record.toolEvidence.length}）
          </Typography>
          <Stack spacing={0.5} sx={{ mt: 0.45 }}>
            {record.toolEvidence.map(ev => (
              <Readout key={ev.toolCallId} mono maxHeight={90}>
                {TOOL_NAMES[ev.tool] || ev.tool} v{ev.version} · {shortId(ev.toolCallId)}
                {ev.resourceVersion ? `\n${ev.resourceVersion}` : ''}
              </Readout>
            ))}
          </Stack>
        </Box>
      )}

      <TraceSection
        title="完整交接正文"
        subtitle="Markdown 渲染"
        defaultExpanded={defaultExpandBody}
        dense
      >
        <Box
          sx={{
            p: 1.5,
            borderRadius: 2,
            bgcolor: '#F8F9FA',
            border: `1px solid ${googleTokens.outline}`
          }}
        >
          <MarkdownView text={record.sourceResult || '交接正文未记录。'} dense />
        </Box>
      </TraceSection>

      <TraceSection title="原始交接记录 JSON" dense>
        <Readout mono maxHeight={200}>
          {JSON.stringify(record, null, 2)}
        </Readout>
      </TraceSection>
    </Box>
  );
}

/** 玻璃盒面板四：多 Agent 结构化交接信封。 */
export default function HandoffEnvelopePanel({
  inbound = [],
  outbound = [],
  defaultExpandBody = false
}) {
  if (inbound.length === 0 && outbound.length === 0) {
    return (
      <EmptyState
        compact
        title="暂无交接记录"
        description="节点运行并向下游交接后，这里会展示结构化交接信封与平台校验清单。"
      />
    );
  }

  return (
    <Stack spacing={1.5}>
      {inbound.map(record => (
        <EnvelopeCard
          key={`in:${record.senderAttemptId}:${record.receiverAttemptId}:${record.dependencyNodeId}`}
          record={record}
          direction="inbound"
          defaultExpandBody={defaultExpandBody}
        />
      ))}
      {outbound.map(record => (
        <EnvelopeCard
          key={`out:${record.senderAttemptId}:${record.receiverAttemptId}:${record.dependencyNodeId}`}
          record={record}
          direction="outbound"
          defaultExpandBody={defaultExpandBody}
        />
      ))}
    </Stack>
  );
}
