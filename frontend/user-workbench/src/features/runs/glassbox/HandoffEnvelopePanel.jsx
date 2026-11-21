import { Box, Chip, Stack, Typography } from '@mui/material';
import CheckCircleRounded from '@mui/icons-material/CheckCircleRounded';
import InsertDriveFileOutlined from '@mui/icons-material/InsertDriveFileOutlined';
import { TraceSection, Readout } from '../../../components/WorkbenchDialog';
import EmptyState from '../../../components/EmptyState';
import MarkdownView from '../../../components/MarkdownView';
import { TOOL_NAMES } from '../../../constants/labels';
import { clock, shortId } from '../../../utils/format';
import { googleTokens } from '../../../theme/googleTheme';

function EnvelopeCard({ record, direction }) {
  const sender = record.senderNodeLabel || record.dependencyNodeLabel || '来源节点';
  const receiver = record.receiverNodeLabel || '接收节点';
  const structured = record.structured;

  return (
    <Box
      sx={{
        p: 1.5,
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
        <Typography variant="subtitle2">
          {direction === 'outbound' ? '发往' : '收到'} · {sender} → {receiver}
        </Typography>
        <Chip
          size="small"
          label={clock(record.acceptedAt) || '已记录'}
          sx={{ bgcolor: googleTokens.successBg, color: googleTokens.successFg }}
        />
      </Stack>

      <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mb: 1 }}>
        发送实例 #{shortId(record.senderAgentId)}（尝试 #{shortId(record.senderAttemptId)}）→
        接收实例 #{shortId(record.receiverAgentId)}（尝试 #{shortId(record.receiverAttemptId)}）
      </Typography>

      {structured && (
        <Stack spacing={1} sx={{ mb: 1 }}>
          <Box sx={{ p: 1, borderRadius: 2, bgcolor: '#F8F9FA' }}>
            <Typography variant="caption" color="text.secondary" sx={{ fontWeight: 600 }}>
              上游核心结论
            </Typography>
            <Typography variant="body2" sx={{ mt: 0.25 }}>
              {structured.summary}
            </Typography>
          </Box>

          {(structured.artifacts ?? []).length > 0 && (
            <Box>
              <Typography variant="caption" color="text.secondary" sx={{ fontWeight: 600 }}>
                涉及文件 / 产物
              </Typography>
              <Stack direction="row" spacing={0.5} sx={{ flexWrap: 'wrap', rowGap: 0.5, mt: 0.4 }}>
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
              <Typography variant="caption" color="text.secondary" sx={{ fontWeight: 600 }}>
                关键决策
              </Typography>
              <Stack spacing={0.35} sx={{ mt: 0.3 }}>
                {structured.decisions.map((item, idx) => (
                  <Typography key={`dec:${idx}`} variant="caption" sx={{ display: 'block' }}>
                    • {item}
                  </Typography>
                ))}
              </Stack>
            </Box>
          )}

          {(structured.openRisks ?? []).length > 0 && (
            <Box sx={{ p: 1, borderRadius: 2, bgcolor: googleTokens.warningBg }}>
              <Typography
                variant="caption"
                sx={{ fontWeight: 600, color: googleTokens.warningFg, display: 'block' }}
              >
                遗留风险 / 待确认
              </Typography>
              {structured.openRisks.map((item, idx) => (
                <Typography
                  key={`risk:${idx}`}
                  variant="caption"
                  sx={{ display: 'block', color: googleTokens.warningFg }}
                >
                  • {item}
                </Typography>
              ))}
            </Box>
          )}

          {(structured.validationChecks ?? []).length > 0 && (
            <Box>
              <Typography variant="caption" color="text.secondary" sx={{ fontWeight: 600 }}>
                平台交接校验清单
              </Typography>
              <Stack direction="row" spacing={0.5} sx={{ flexWrap: 'wrap', rowGap: 0.5, mt: 0.4 }}>
                {structured.validationChecks.map((chk, idx) => (
                  <Chip
                    key={`hc:${idx}`}
                    size="small"
                    icon={
                      chk.status === 'PASS' ? (
                        <CheckCircleRounded sx={{ fontSize: 14, color: googleTokens.successFg }} />
                      ) : undefined
                    }
                    label={`${chk.label} · ${chk.status}`}
                    sx={{
                      bgcolor:
                        chk.status === 'PASS' ? googleTokens.successBg : googleTokens.neutralBg,
                      color: chk.status === 'PASS' ? googleTokens.successFg : googleTokens.neutralFg
                    }}
                  />
                ))}
              </Stack>
            </Box>
          )}
        </Stack>
      )}

      {(record.toolEvidence ?? []).length > 0 && (
        <Box sx={{ mb: 1 }}>
          <Typography variant="caption" color="text.secondary" sx={{ fontWeight: 600 }}>
            随信封附带的工具证据（{record.toolEvidence.length}）
          </Typography>
          <Stack spacing={0.5} sx={{ mt: 0.4 }}>
            {record.toolEvidence.map(ev => (
              <Readout key={ev.toolCallId} mono maxHeight={90}>
                {TOOL_NAMES[ev.tool] || ev.tool} v{ev.version} · {shortId(ev.toolCallId)}
                {ev.resourceVersion ? `\n${ev.resourceVersion}` : ''}
              </Readout>
            ))}
          </Stack>
        </Box>
      )}

      <TraceSection title="完整交接正文与原始 JSON" dense>
        <Stack spacing={0.75}>
          <Box
            sx={{
              p: 1.25,
              borderRadius: 2,
              bgcolor: '#F8F9FA',
              border: `1px solid ${googleTokens.outline}`
            }}
          >
            <MarkdownView text={record.sourceResult || '交接正文未记录。'} maxHeight={240} dense />
          </Box>
          <Readout mono maxHeight={160}>
            {JSON.stringify(record, null, 2)}
          </Readout>
        </Stack>
      </TraceSection>
    </Box>
  );
}

/** 玻璃盒面板四：多 Agent 结构化交接信封。 */
export default function HandoffEnvelopePanel({ inbound = [], outbound = [] }) {
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
        />
      ))}
      {outbound.map(record => (
        <EnvelopeCard
          key={`out:${record.senderAttemptId}:${record.receiverAttemptId}:${record.dependencyNodeId}`}
          record={record}
          direction="outbound"
        />
      ))}
    </Stack>
  );
}
