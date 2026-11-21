import { Alert, Box, Chip, Stack, Typography } from '@mui/material';
import { TraceSection, Readout } from '../../../components/WorkbenchDialog';
import EmptyState from '../../../components/EmptyState';
import MarkdownView from '../../../components/MarkdownView';
import { duration } from '../../../utils/format';
import { googleTokens } from '../../../theme/googleTheme';

const roleStyle = role => {
  const upper = String(role || '').toUpperCase();
  if (upper === 'SYSTEM') return { bg: '#E8F0FE', fg: '#1967D2', label: 'SYSTEM' };
  if (upper === 'ASSISTANT') return { bg: '#E6F4EA', fg: '#137333', label: 'ASSISTANT' };
  if (upper === 'TOOL') return { bg: '#FEF7E0', fg: '#B06000', label: 'TOOL' };
  return { bg: '#F1F3F4', fg: '#3C4043', label: upper || 'USER' };
};

/** 玻璃盒面板二：模型调用透视（发给模型的 Messages/Schema + 模型原始回复与 tool_calls）。 */
export default function LlmTracePanel({ models }) {
  if (!models || models.length === 0) {
    return (
      <EmptyState
        compact
        title="暂无模型调用记录"
        description="该节点尚未发起模型调用，或为系统检查节点。"
      />
    );
  }

  return (
    <Stack spacing={1.5}>
      {models.map((call, index) => {
        const payload = call.payload;
        return (
          <Box
            key={call.id}
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
              sx={{
                alignItems: 'center',
                justifyContent: 'space-between',
                flexWrap: 'wrap',
                mb: 1
              }}
            >
              <Typography variant="subtitle2">
                第 {index + 1} 次调用 · {call.name || 'model'}
              </Typography>
              <Stack direction="row" spacing={0.5}>
                <Chip size="small" label={duration(call.durationMs)} />
                <Chip
                  size="small"
                  label={`In ${call.inputTokens ?? '—'} / Out ${call.outputTokens ?? '—'}`}
                />
              </Stack>
            </Stack>

            {call.errorMessage && (
              <Alert severity="error" sx={{ mb: 1 }}>
                {call.errorMessage}
              </Alert>
            )}

            {!payload ? (
              <Typography variant="caption" color="text.secondary">
                历史运行未采集模型 I/O 快照；新创建的运行会自动保存脱敏后的请求与响应原文。
              </Typography>
            ) : (
              <Stack spacing={1}>
                {payload.truncated && (
                  <Alert severity="info" sx={{ py: 0 }}>
                    内容较长已按 64 KB 上限自动脱敏截断。
                  </Alert>
                )}

                <TraceSection
                  title={`发给模型的 Messages（${payload.requestMessages?.length ?? 0} 条）`}
                  subtitle="按角色分色"
                  dense
                >
                  <Stack spacing={0.9}>
                    {(payload.requestMessages ?? []).map((msg, idx) => {
                      const tone = roleStyle(msg.role);
                      return (
                        <Box
                          key={`msg:${idx}`}
                          sx={{
                            border: `1px solid ${googleTokens.outline}`,
                            borderRadius: 2,
                            overflow: 'hidden'
                          }}
                        >
                          <Stack
                            direction="row"
                            spacing={0.75}
                            sx={{
                              px: 1,
                              py: 0.45,
                              bgcolor: tone.bg,
                              color: tone.fg,
                              alignItems: 'center',
                              justifyContent: 'space-between'
                            }}
                          >
                            <Typography variant="caption" sx={{ fontWeight: 600 }}>
                              {tone.label}
                              {msg.name ? ` · ${msg.name}` : ''}
                            </Typography>
                            <Typography variant="caption">{msg.charCount ?? 0} 字符</Typography>
                          </Stack>
                          <Box sx={{ p: 1, bgcolor: '#FAFAFA' }}>
                            <Readout mono maxHeight={180}>
                              {msg.content || '(空)'}
                            </Readout>
                          </Box>
                        </Box>
                      );
                    })}
                  </Stack>
                </TraceSection>

                {(payload.requestTools ?? []).length > 0 && (
                  <TraceSection
                    title={`注入的工具 Schema（${payload.requestTools.length} 个）`}
                    dense
                  >
                    <Stack spacing={0.75}>
                      {payload.requestTools.map((tool, idx) => (
                        <Box key={`${tool.name}:${idx}`}>
                          <Typography variant="caption" sx={{ fontWeight: 600 }}>
                            {tool.name}
                          </Typography>
                          {tool.description && (
                            <Typography
                              variant="caption"
                              color="text.secondary"
                              sx={{ display: 'block', mb: 0.4 }}
                            >
                              {tool.description}
                            </Typography>
                          )}
                          <Readout mono maxHeight={130}>
                            {tool.parametersJson}
                          </Readout>
                        </Box>
                      ))}
                    </Stack>
                  </TraceSection>
                )}

                <Box>
                  <Typography variant="caption" color="text.secondary" sx={{ fontWeight: 600 }}>
                    模型原始返回
                  </Typography>
                  {payload.responseContent && (
                    <Box
                      sx={{
                        mt: 0.4,
                        p: 1.25,
                        borderRadius: 2,
                        bgcolor: '#F8F9FA',
                        border: `1px solid ${googleTokens.outline}`
                      }}
                    >
                      <MarkdownView text={payload.responseContent} maxHeight={240} dense />
                    </Box>
                  )}
                  {(payload.responseToolCalls ?? []).length > 0 && (
                    <Stack spacing={0.6} sx={{ mt: 0.75 }}>
                      <Typography
                        variant="caption"
                        sx={{ color: googleTokens.primary, fontWeight: 600 }}
                      >
                        模型决定发起 {payload.responseToolCalls.length} 次工具调用：
                      </Typography>
                      {payload.responseToolCalls.map((tc, idx) => (
                        <Box
                          key={`${tc.id || tc.name}:${idx}`}
                          sx={{
                            p: 1,
                            borderRadius: 2,
                            bgcolor: '#E8F0FE',
                            border: `1px solid ${googleTokens.primarySoft}`
                          }}
                        >
                          <Typography
                            variant="caption"
                            sx={{
                              fontFamily: googleTokens.mono,
                              fontWeight: 600,
                              color: '#041E49'
                            }}
                          >
                            tool_call: {tc.name}
                          </Typography>
                          <Readout mono maxHeight={120}>
                            {tc.argumentsJson}
                          </Readout>
                        </Box>
                      ))}
                    </Stack>
                  )}
                </Box>
              </Stack>
            )}
          </Box>
        );
      })}
    </Stack>
  );
}
