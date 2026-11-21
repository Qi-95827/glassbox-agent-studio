import { Alert, Box, Chip, Stack, Tooltip, Typography } from '@mui/material';
import CheckCircleRounded from '@mui/icons-material/CheckCircleRounded';
import CancelRounded from '@mui/icons-material/CancelRounded';
import RemoveCircleOutlineRounded from '@mui/icons-material/RemoveCircleOutlineRounded';
import { TraceSection, Readout } from '../../../components/WorkbenchDialog';
import EmptyState from '../../../components/EmptyState';
import { TOOL_NAMES } from '../../../constants/labels';
import { duration } from '../../../utils/format';
import { googleTokens } from '../../../theme/googleTheme';

function GateBadge({ check }) {
  const pass = check.status === 'PASS';
  const deny = check.status === 'DENY';
  const bg = pass ? googleTokens.successBg : deny ? googleTokens.errorBg : googleTokens.neutralBg;
  const fg = pass ? googleTokens.successFg : deny ? googleTokens.errorFg : googleTokens.neutralFg;
  const icon = pass ? (
    <CheckCircleRounded sx={{ fontSize: 14, color: fg }} />
  ) : deny ? (
    <CancelRounded sx={{ fontSize: 14, color: fg }} />
  ) : (
    <RemoveCircleOutlineRounded sx={{ fontSize: 14, color: fg }} />
  );
  return (
    <Tooltip title={check.detail || check.label} arrow>
      <Chip
        size="small"
        icon={icon}
        label={`${check.label} · ${check.status}`}
        sx={{ bgcolor: bg, color: fg, fontWeight: 500 }}
      />
    </Tooltip>
  );
}

/** 玻璃盒面板三：ToolGateway 三段式透明卡片（入参 JSON → 6 道安检门 → 完整返回值与回执）。 */
export default function ToolGatewayPanel({ tools }) {
  if (!tools || tools.length === 0) {
    return (
      <EmptyState
        compact
        title="暂无工具调用记录"
        description="该节点未调用外部工具，或工具尚未触发。"
      />
    );
  }

  return (
    <Stack spacing={1.5}>
      {tools.map((call, index) => {
        const trace = call.trace;
        const checks = trace?.gatewayChecks ?? [];
        const failed = call.status === 'FAILED';
        return (
          <Box
            key={call.id}
            sx={{
              p: 1.5,
              borderRadius: 3,
              bgcolor: '#FFFFFF',
              border: `1px solid ${failed ? '#F2B8B5' : googleTokens.outline}`
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
                #{index + 1} · {TOOL_NAMES[call.name] || call.name}
                {call.version != null ? ` v${call.version}` : ''}
              </Typography>
              <Stack direction="row" spacing={0.5}>
                <Chip
                  size="small"
                  label={call.status}
                  sx={{
                    bgcolor: failed ? googleTokens.errorBg : googleTokens.successBg,
                    color: failed ? googleTokens.errorFg : googleTokens.successFg
                  }}
                />
                <Chip size="small" label={duration(call.durationMs)} />
              </Stack>
            </Stack>

            <Typography variant="caption" color="text.secondary" sx={{ fontWeight: 600 }}>
              ① 请求入参 / 指纹
            </Typography>
            <Box sx={{ mt: 0.4, mb: 1 }}>
              <Readout mono maxHeight={110}>
                {trace?.inputArgs || '(历史运行未采集入参)'}
              </Readout>
            </Box>

            <Typography variant="caption" color="text.secondary" sx={{ fontWeight: 600 }}>
              ② ToolGateway 安检门（悬停查看判定依据）
            </Typography>
            {checks.length > 0 ? (
              <Stack spacing={0.6} sx={{ mt: 0.5, mb: 1 }}>
                <Stack direction="row" spacing={0.5} sx={{ flexWrap: 'wrap', rowGap: 0.5 }}>
                  {checks.map((chk, idx) => (
                    <GateBadge key={`${chk.gate}:${idx}`} check={chk} />
                  ))}
                </Stack>
                {checks
                  .filter(chk => chk.status === 'DENY')
                  .map((chk, idx) => (
                    <Alert key={`deny:${idx}`} severity="error" sx={{ py: 0.25 }}>
                      {chk.label}拦截：{chk.detail}
                    </Alert>
                  ))}
              </Stack>
            ) : (
              <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mb: 1 }}>
                历史运行未采集网关逐项检查明细。
              </Typography>
            )}

            <Typography variant="caption" color="text.secondary" sx={{ fontWeight: 600 }}>
              ③ 执行返回值与资源回执
            </Typography>
            {call.errorMessage && (
              <Alert severity="error" sx={{ mt: 0.5, mb: 0.5 }}>
                {call.errorMessage}
              </Alert>
            )}
            <Box sx={{ mt: 0.4 }}>
              <Readout mono maxHeight={160}>
                {trace?.fullResult || call.result || '(无返回内容)'}
              </Readout>
            </Box>
            {call.resourceVersion && (
              <Box sx={{ mt: 0.75 }}>
                <TraceSection title="文件资源版本 / SHA256 回执" dense>
                  <Readout mono maxHeight={110}>
                    {call.resourceVersion}
                  </Readout>
                </TraceSection>
              </Box>
            )}
          </Box>
        );
      })}
    </Stack>
  );
}
