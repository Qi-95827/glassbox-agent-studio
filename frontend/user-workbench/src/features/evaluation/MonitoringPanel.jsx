import { useState } from 'react';
import { Alert, Box, Button, Chip, Stack, Typography } from '@mui/material';
import { useQuery } from '@tanstack/react-query';
import { useMonitoring, useMonitoringAlerts } from '../../hooks/useEvaluations';
import { evaluationApi } from '../../api/evaluations';
import { RUN_STATES, STOP_CAUSES } from '../../constants/labels';
import { googleTokens } from '../../theme/googleTheme';
import { dateTime } from '../../utils/format';

function StatCard({ label, value, detail }) {
  return (
    <Box
      sx={{
        bgcolor: '#FFFFFF',
        border: `1px solid ${googleTokens.outline}`,
        borderRadius: 4,
        p: 2
      }}
    >
      <Typography variant="caption" color="text.secondary">
        {label}
      </Typography>
      <Typography sx={{ fontSize: 26, fontWeight: 500, lineHeight: 1.3 }}>{value}</Typography>
      {detail && (
        <Typography variant="caption" color="text.secondary">
          {detail}
        </Typography>
      )}
    </Box>
  );
}

function CountList({ rows, labels }) {
  if (!rows?.length) {
    return (
      <Typography variant="body2" color="text.secondary">
        暂无记录。
      </Typography>
    );
  }
  return (
    <Stack direction="row" spacing={0.75} useFlexGap sx={{ flexWrap: 'wrap' }}>
      {rows.map((row, index) => (
        <Chip
          key={`${row.status || row.cause || index}`}
          size="small"
          label={`${labels[row.status || row.cause] || row.status || row.cause} ${row.count}`}
          sx={{ bgcolor: '#F1F3F4' }}
        />
      ))}
    </Stack>
  );
}

/** 运维监控：本机数据库统计、告警、审计与系统自检。 */
export default function MonitoringPanel() {
  const monitoring = useMonitoring();
  const alerts = useMonitoringAlerts();
  const audit = useQuery({
    queryKey: ['audit', 20],
    queryFn: () => evaluationApi.audit(20)
  });
  const [selfCheck, setSelfCheck] = useState(null);
  const [checking, setChecking] = useState(false);

  const data = monitoring.data;
  const total = values => (values ?? []).reduce((sum, row) => sum + (row.count ?? 0), 0);

  const runSelfCheck = async () => {
    setChecking(true);
    try {
      setSelfCheck(await evaluationApi.selfCheck());
    } catch (error) {
      setSelfCheck({
        status: 'ERROR',
        checks: [{ name: '请求失败', status: 'FAILED', detail: error.message }]
      });
    } finally {
      setChecking(false);
    }
  };

  return (
    <Stack spacing={2}>
      <Alert severity="info">
        统计本机数据库当前保留记录，每 5 秒刷新；已知用量不代表完整消耗，当前不包含计费。
      </Alert>

      {monitoring.isError && <Alert severity="error">{monitoring.error.message}</Alert>}
      {data && (
        <>
          <Box
            sx={{
              display: 'grid',
              gridTemplateColumns: {
                xs: 'repeat(2, minmax(0,1fr))',
                md: 'repeat(5, minmax(0,1fr))'
              },
              gap: 1.5
            }}
          >
            <StatCard
              label="运行"
              value={total(data.runs)}
              detail={`${data.runs?.length ?? 0} 种状态`}
            />

            <StatCard label="Agent 调用" value={total(data.agents)} />
            <StatCard label="模型调用" value={total(data.models)} />
            <StatCard label="工具调用" value={total(data.tools)} />
            <StatCard label="评测案例" value={total(data.evaluationCases)} />
          </Box>

          <Box
            sx={{
              display: 'grid',
              gridTemplateColumns: { xs: '1fr', lg: 'repeat(2, minmax(0,1fr))' },
              gap: 2
            }}
          >
            <Box
              sx={{
                bgcolor: '#FFFFFF',
                border: `1px solid ${googleTokens.outline}`,
                borderRadius: 4,
                p: 2.5
              }}
            >
              <Typography sx={{ fontSize: 14, fontWeight: 600, mb: 1 }}>运行状态分布</Typography>
              <CountList rows={data.runs} labels={RUN_STATES} />
              <Typography sx={{ fontSize: 13, fontWeight: 600, mt: 2, mb: 1 }}>停止原因</Typography>
              <Stack direction="row" spacing={0.75} useFlexGap sx={{ flexWrap: 'wrap' }}>
                {(data.stopCauses ?? []).map(row => (
                  <Chip
                    key={row.cause}
                    size="small"
                    label={`${STOP_CAUSES[row.cause] || row.cause} ${row.count}`}
                    sx={{ bgcolor: '#FEF7E0', color: '#B06000' }}
                  />
                ))}
                {(data.stopCauses ?? []).length === 0 && (
                  <Typography variant="body2" color="text.secondary">
                    暂无已分类停止。
                  </Typography>
                )}
              </Stack>
              <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mt: 1 }}>
                未记录 {data.missingStopCauses ?? '未知'}；编码与状态不一致{' '}
                {data.inconsistentStopCauses ?? '未知'}。仅统计当前保留的停止记录。
              </Typography>
            </Box>

            <Box
              sx={{
                bgcolor: '#FFFFFF',
                border: `1px solid ${googleTokens.outline}`,
                borderRadius: 4,
                p: 2.5
              }}
            >
              <Typography sx={{ fontSize: 14, fontWeight: 600, mb: 1 }}>模型与工具用量</Typography>
              <Stack spacing={0.4}>
                <Typography variant="body2">
                  已知输入 Token：{data.usage?.knownInputTokens ?? '无记录'}；未知{' '}
                  {data.usage?.unknownInputCalls ?? 0} 次
                </Typography>
                <Typography variant="body2">
                  已知输出 Token：{data.usage?.knownOutputTokens ?? '无记录'}；未知{' '}
                  {data.usage?.unknownOutputCalls ?? 0} 次
                </Typography>
                <Typography variant="body2">
                  已知模型耗时：{data.usage?.knownModelDurationMs ?? '无记录'} 毫秒；未知{' '}
                  {data.usage?.unknownModelDurationCalls ?? 0} 次
                </Typography>
                <Typography variant="body2">
                  已知工具耗时：{data.usage?.knownToolDurationMs ?? '无记录'} 毫秒；未知{' '}
                  {data.usage?.unknownToolDurationCalls ?? 0} 次
                </Typography>
              </Stack>
              <Typography variant="caption" color="text.secondary">
                部分调用用量未知时，已知合计不是完整总量。
              </Typography>
            </Box>
          </Box>
        </>
      )}

      <Box
        sx={{
          bgcolor: '#FFFFFF',
          border: `1px solid ${googleTokens.outline}`,
          borderRadius: 4,
          p: 2.5
        }}
      >
        <Typography sx={{ fontSize: 14, fontWeight: 600, mb: 1 }}>告警</Typography>
        <Typography variant="caption" color="text.secondary">
          每条告警都关联运行或受控动作；没有记录不代表系统健康。
        </Typography>
        <Stack spacing={1} sx={{ mt: 1 }}>
          {(alerts.data ?? []).length === 0 && (
            <Typography variant="body2" color="text.secondary">
              暂无已记录告警。
            </Typography>
          )}
          {(alerts.data ?? []).map((alert, index) => (
            <Box
              key={`${alert.code}-${alert.targetId}-${index}`}
              sx={{ p: 1.25, borderRadius: 2.5, bgcolor: '#F8F9FA' }}
            >
              <Stack direction="row" spacing={0.75} sx={{ alignItems: 'center', flexWrap: 'wrap' }}>
                <Chip
                  size="small"
                  label={alert.severity}
                  sx={{
                    bgcolor: alert.severity === 'CRITICAL' ? '#FCE8E6' : '#FEF7E0',
                    color: alert.severity === 'CRITICAL' ? '#C5221F' : '#B06000'
                  }}
                />

                <Typography variant="body2" sx={{ fontWeight: 600 }}>
                  {alert.title}
                </Typography>
              </Stack>
              <Typography variant="body2" color="text.secondary">
                {alert.detail}
              </Typography>
            </Box>
          ))}
        </Stack>
      </Box>

      <Box
        sx={{
          bgcolor: '#FFFFFF',
          border: `1px solid ${googleTokens.outline}`,
          borderRadius: 4,
          p: 2.5
        }}
      >
        <Stack
          direction="row"
          sx={{ mb: 1, justifyContent: 'space-between', alignItems: 'center' }}
        >
          <Typography sx={{ fontSize: 14, fontWeight: 600 }}>系统自检</Typography>
          <Button variant="outlined" loading={checking} onClick={runSelfCheck}>
            运行自检
          </Button>
        </Stack>
        {selfCheck ? (
          <>
            <Typography variant="body2" sx={{ mb: 0.75 }}>
              总体：{selfCheck.status}
            </Typography>
            <Stack spacing={0.6}>
              {(selfCheck.checks ?? []).map(check => (
                <Typography key={check.name} variant="body2">
                  {check.status === 'PASS' || check.status === 'OK' ? '通过' : check.status} ·{' '}
                  {check.name}
                  {check.detail ? `：${check.detail}` : ''}
                </Typography>
              ))}
            </Stack>
          </>
        ) : (
          <Typography variant="body2" color="text.secondary">
            点击运行自检，检查本地数据库、目录与必要配置；只读检查，不会修改数据。
          </Typography>
        )}
      </Box>

      <Box
        sx={{
          bgcolor: '#FFFFFF',
          border: `1px solid ${googleTokens.outline}`,
          borderRadius: 4,
          p: 2.5
        }}
      >
        <Typography sx={{ fontSize: 14, fontWeight: 600, mb: 1 }}>最近审计记录</Typography>
        <Stack spacing={0.75}>
          {(audit.data ?? []).slice(0, 10).map((row, index) => (
            <Stack key={row.id || index} direction="row" spacing={1} sx={{ flexWrap: 'wrap' }}>
              <Typography variant="caption" color="text.secondary">
                {dateTime(row.createdAt || row.CREATED_AT || row.at)}
              </Typography>
              <Typography variant="body2">
                {row.action || row.ACTION || row.summary || JSON.stringify(row).slice(0, 180)}
              </Typography>
            </Stack>
          ))}
          {(audit.data ?? []).length === 0 && (
            <Typography variant="body2" color="text.secondary">
              暂无审计记录。
            </Typography>
          )}
        </Stack>
      </Box>
    </Stack>
  );
}
