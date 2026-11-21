import { useState } from 'react';
import { Alert, Box, Button, Stack, Typography } from '@mui/material';
import { useQuery } from '@tanstack/react-query';
import { bugsApi } from '../../api/bugs';
import { evaluationApi } from '../../api/evaluations';
import { Readout, TraceSection } from '../../components/WorkbenchDialog';
import { useToast } from '../../components/ToastProvider';
import { oneLine } from '../../utils/format';

const checkStatusLabels = {
  NEEDS_INFO: '缺少信息',
  FAILURES_OBSERVED: '已观察到目标失败',
  TARGET_MISMATCH: '未匹配目标现象',
  TARGET_NOT_EXECUTED: '目标用例未执行',
  TARGET_SKIPPED: '目标用例被跳过',
  NOT_REPRODUCED: '未观察到失败',
  PASSED: '通过',
  FAILED: '失败',
  ERROR: '环境或执行错误',
  TIMEOUT: '超时',
  INTERRUPTED: '中断',
  STALE: '代码版本已变化'
};

const observationLabels = {
  FAILURE_OBSERVED: '曾观察到测试失败',
  FAILURE_NOT_OBSERVED: '未观察到测试失败',
  CONFLICT: '相同代码状态的观察结果冲突',
  UNCONFIRMED: '证据不足',
  NOT_APPLICABLE: '不适用'
};

/** 当前运行的模拟 Bug 检测记录与证据评估。 */
export default function BugEvidencePanel({ runId }) {
  const toast = useToast();
  const [assessment, setAssessment] = useState(null);
  const [assessing, setAssessing] = useState(false);
  const checks = useQuery({
    queryKey: ['bug-checks', runId],
    queryFn: () => bugsApi.checksByRun(runId),
    enabled: Boolean(runId)
  });
  const delivery = useQuery({
    queryKey: ['bug-delivery', runId],
    queryFn: () => bugsApi.delivery(runId),
    enabled: Boolean(runId),
    retry: 0
  });

  const rows = checks.data ?? [];

  const assess = async () => {
    setAssessing(true);
    try {
      setAssessment(await evaluationApi.bugEvidence(runId));
    } catch (error) {
      toast.error(error.message);
    } finally {
      setAssessing(false);
    }
  };

  return (
    <Stack spacing={1.4}>
      {delivery.data?.intake && (
        <Box
          sx={{
            p: 1.5,
            bgcolor: '#FBFCFD',
            border: '1px solid',
            borderColor: 'divider',
            borderRadius: 3
          }}
        >
          <Typography variant="body2" sx={{ fontWeight: 600 }}>
            分诊与交付
          </Typography>
          <Typography variant="caption" color="text.secondary" sx={{ display: 'block' }}>
            原始描述：{delivery.data.intake.originalDescription}
          </Typography>
          <Typography variant="caption" color="text.secondary" sx={{ display: 'block' }}>
            来源：{delivery.data.intake.source} v{delivery.data.intake.sourceVersion} · 第{' '}
            {delivery.data.intake.submissionSeq} 次提交
          </Typography>
          <Typography variant="caption" color="text.secondary" sx={{ display: 'block' }}>
            分诊：{delivery.data.triage?.label || '待分诊'}；交付结论：
            {delivery.data.conclusionLabel || '未记录'}
          </Typography>
        </Box>
      )}
      <Button
        size="small"
        variant="outlined"
        loading={assessing}
        onClick={assess}
        sx={{ alignSelf: 'flex-start' }}
      >
        评估当前证据
      </Button>
      {assessment && (
        <Box sx={{ p: 1.5, bgcolor: '#F8F9FA', borderRadius: 3 }}>
          <Typography variant="body2" sx={{ fontWeight: 600 }}>
            证据快照
          </Typography>
          <Typography variant="body2">
            失败观察：{observationLabels[assessment.observation] || '未知'}
          </Typography>
          <Typography variant="caption" color="text.secondary" sx={{ display: 'block' }}>
            这是点击时的快照，不证明特定 Bug 已复现或修复；代码变化后请重新评估。观察证据{' '}
            {assessment.observationEvidenceIds?.length ?? 0} 条，回归证据{' '}
            {assessment.regressionEvidenceIds?.length ?? 0} 条。
          </Typography>
        </Box>
      )}
      {rows.map(check => (
        <TraceSection
          key={check.ID}
          title={`${check.KIND === 'REPRODUCE' ? '失败观察' : '回归检查'} · ${checkStatusLabels[check.STATUS] || check.STATUS}`}
          subtitle={`${check.DURATION_MS} 毫秒 · ${check.CREATED_AT || ''}`}
        >
          <Stack spacing={0.7}>
            <Typography variant="caption" color="text.secondary">
              证据 ID：{check.ID}；代码状态摘要：{check.WORKSPACE_FINGERPRINT || '未知'}
            </Typography>
            {(check.CASES ?? []).map(test => (
              <Typography key={`${check.ID}-${test.SEQUENCE_NO}`} variant="body2">
                <code>{test.TEST_ID}</code> · {test.RESULT}
                {test.MESSAGE ? `：${oneLine(test.MESSAGE, 180)}` : ''}
              </Typography>
            ))}
            {check.OUTPUT && <Readout maxHeight={160}>{check.OUTPUT}</Readout>}
          </Stack>
        </TraceSection>
      ))}
      {checks.isLoading && (
        <Typography variant="body2" color="text.secondary">
          正在读取检测记录…
        </Typography>
      )}
      {!checks.isLoading && rows.length === 0 && (
        <Alert severity="info">本次运行没有可确认归属的检测记录。</Alert>
      )}
      <Typography variant="caption" color="text.secondary">
        仅显示明确关联本次运行的检测；历史手动检测不会自动归属，测试结果需要结合代码版本与人工审查。
      </Typography>
    </Stack>
  );
}
