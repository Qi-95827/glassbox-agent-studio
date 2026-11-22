import { useState } from 'react';
import { Alert, Box, Button, Chip, Collapse, Stack, Typography } from '@mui/material';
import CheckCircleRounded from '@mui/icons-material/CheckCircleRounded';
import CancelRounded from '@mui/icons-material/CancelRounded';
import RemoveCircleOutlineRounded from '@mui/icons-material/RemoveCircleOutlineRounded';
import HelpOutlineRounded from '@mui/icons-material/HelpOutlineRounded';
import { TraceSection, Readout } from '../../../components/WorkbenchDialog';
import EmptyState from '../../../components/EmptyState';
import { TOOL_NAMES } from '../../../constants/labels';
import { duration } from '../../../utils/format';
import { googleTokens } from '../../../theme/googleTheme';
import { GATEWAY_CHECK_RULES } from './checkExplanations';

function gateTone(status) {
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

function ToolCallCard({ call, index }) {
  const [showRules, setShowRules] = useState(false);
  const trace = call.trace;
  const checks = trace?.gatewayChecks ?? [];
  const failed = call.status === 'FAILED';

  return (
    <Box
      sx={{
        p: 2,
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
          mb: 1.25
        }}
      >
        <Typography variant="subtitle2" sx={{ fontSize: 14.5 }}>
          #{index + 1} · {TOOL_NAMES[call.name] || call.name}
          {call.version != null ? ` v${call.version}` : ''}
        </Typography>
        <Stack direction="row" spacing={0.5}>
          <Chip
            size="small"
            label={call.status}
            sx={{
              bgcolor: failed ? googleTokens.errorBg : googleTokens.successBg,
              color: failed ? googleTokens.errorFg : googleTokens.successFg,
              fontWeight: 600
            }}
          />
          <Chip size="small" label={duration(call.durationMs)} />
        </Stack>
      </Stack>

      <Typography variant="caption" color="text.secondary" sx={{ fontWeight: 600 }}>
        ① 请求入参 / 指纹
      </Typography>
      <Box sx={{ mt: 0.45, mb: 1.25 }}>
        <Readout mono maxHeight={120}>
          {trace?.inputArgs || '(历史运行未采集入参)'}
        </Readout>
      </Box>

      <Stack
        direction="row"
        sx={{ alignItems: 'center', justifyContent: 'space-between', mb: 0.6 }}
      >
        <Typography variant="caption" color="text.secondary" sx={{ fontWeight: 600 }}>
          ② ToolGateway 安检门（本次放行 / 拦截依据）
        </Typography>
        {checks.length > 0 && (
          <Button
            size="small"
            startIcon={<HelpOutlineRounded sx={{ fontSize: 14 }} />}
            onClick={() => setShowRules(prev => !prev)}
            sx={{ py: 0.15, px: 0.75, fontSize: 12, minHeight: 26 }}
          >
            {showRules ? '收起判定标准' : '查看怎么算 PASS'}
          </Button>
        )}
      </Stack>

      {checks.length > 0 ? (
        <Stack spacing={0.7} sx={{ mb: 1.25 }}>
          {checks.map((chk, idx) => {
            const tone = gateTone(chk.status);
            const rule = GATEWAY_CHECK_RULES[chk.gate];
            return (
              <Box
                key={`${chk.gate}:${idx}`}
                sx={{
                  p: 1.05,
                  borderRadius: 2,
                  bgcolor: chk.status === 'DENY' ? '#FFF8F7' : '#FAFAFA',
                  border: `1px solid ${tone.border}`
                }}
              >
                <Stack
                  direction="row"
                  spacing={0.75}
                  sx={{ alignItems: 'center', flexWrap: 'wrap', rowGap: 0.45 }}
                >
                  <Chip
                    size="small"
                    icon={tone.icon}
                    label={`${chk.label} · ${chk.status}`}
                    sx={{ bgcolor: tone.bg, color: tone.fg, fontWeight: 600 }}
                  />
                  <Typography
                    variant="caption"
                    sx={{
                      color: chk.status === 'DENY' ? googleTokens.errorFg : googleTokens.text,
                      lineHeight: 1.5,
                      flex: 1,
                      minWidth: 180
                    }}
                  >
                    {chk.detail || '已完成该道安检门校验。'}
                  </Typography>
                </Stack>

                {rule && (
                  <Collapse in={showRules}>
                    <Box
                      sx={{
                        mt: 0.75,
                        pt: 0.7,
                        borderTop: `1px dashed ${googleTokens.outline}`,
                        color: googleTokens.textSecondary
                      }}
                    >
                      <Typography variant="caption" sx={{ display: 'block', lineHeight: 1.55 }}>
                        <strong>为什么检查：</strong>
                        {rule.purpose}
                      </Typography>
                      <Typography variant="caption" sx={{ display: 'block', lineHeight: 1.55 }}>
                        <strong>怎么算 PASS：</strong>
                        {rule.passRule}
                      </Typography>
                      <Typography variant="caption" sx={{ display: 'block', lineHeight: 1.55 }}>
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
      ) : (
        <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mb: 1.25 }}>
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
      <Box sx={{ mt: 0.45 }}>
        <Readout mono maxHeight={180}>
          {trace?.fullResult || call.result || '(无返回内容)'}
        </Readout>
      </Box>
      {call.resourceVersion && (
        <Box sx={{ mt: 0.75 }}>
          <TraceSection title="文件资源版本 / SHA256 回执" dense>
            <Readout mono maxHeight={120}>
              {call.resourceVersion}
            </Readout>
          </TraceSection>
        </Box>
      )}
    </Box>
  );
}

/** 玻璃盒面板三：ToolGateway 三段式透明卡片（入参 JSON → 安检门与判定依据 → 完整返回值与回执）。 */
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
      {tools.map((call, index) => (
        <ToolCallCard key={call.id} call={call} index={index} />
      ))}
    </Stack>
  );
}
