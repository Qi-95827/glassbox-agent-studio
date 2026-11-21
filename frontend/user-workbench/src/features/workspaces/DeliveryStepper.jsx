import { Box, Step, StepLabel, Stepper, Typography, useMediaQuery } from '@mui/material';
import { useTheme } from '@mui/material/styles';
import {
  useWorkspaceApprovals,
  useWorkspaceCommits,
  useWorkspaceDeliveries,
  useWorkspaceStatus,
  useWorkspaceUntracked
} from '../../hooks/useWorkspaces';
import { dateTime, shortId } from '../../utils/format';
import { googleTokens } from '../../theme/googleTheme';

const deliveryLabels = {
  PENDING: '待批准',
  APPROVED: '已批准',
  REJECTED: '已拒绝',
  STARTED: '执行中',
  CONFIRMED: '已确认',
  CONFLICT: '冲突，需核对',
  UNKNOWN: '结果未知，需核对'
};

const approvalLabels = {
  PENDING: '待决定',
  APPROVED: '已批准',
  REJECTED: '已拒绝',
  STALE: '已失效，需重新审查'
};

/** `/commits` 可能同时包含推送/合并动作；本地提交步骤只读 LOCAL_COMMIT。 */
export function isLocalCommit(row) {
  return (row.kind || row.KIND || 'LOCAL_COMMIT') === 'LOCAL_COMMIT';
}

/** 审批拒绝/失效时停在审批步骤；若工作区已干净且已完成本地提交，则审批已被本次提交消费，视为完成。 */
export function deliveryActiveStep({
  diffReviewed,
  approvalStatus,
  committed,
  deliveryStatus,
  hasDiff = true
}) {
  if (approvalStatus === 'REJECTED') return 1;
  if (approvalStatus === 'STALE') {
    if (!hasDiff && committed) return deliveryStatus === 'CONFIRMED' ? 4 : 3;
    return 1;
  }
  if (approvalStatus !== 'APPROVED') return diffReviewed ? 1 : 0;
  if (!committed) return 2;
  return deliveryStatus === 'CONFIRMED' ? 4 : 3;
}

/**
 * Git 交付进度：差异核对 → 审查批准 → 本地提交 → 推送/合并。
 * 每一步只由服务端已保存的差异、审批、提交或交付记录推导，不根据按钮点击推断成功。
 */
export default function DeliveryStepper({ workspaceId }) {
  const theme = useTheme();
  const narrow = useMediaQuery(theme.breakpoints.down('lg'));
  const status = useWorkspaceStatus(workspaceId);
  const approvals = useWorkspaceApprovals(workspaceId);
  const commits = useWorkspaceCommits(workspaceId);
  const deliveries = useWorkspaceDeliveries(workspaceId);
  const untracked = useWorkspaceUntracked(workspaceId);

  const changes = status.data?.changes;
  const untrackedRows = untracked.data ?? [];
  const approvalRows = [...(approvals.data ?? [])].sort((left, right) =>
    String(right.UPDATED_AT || right.CREATED_AT).localeCompare(
      String(left.UPDATED_AT || left.CREATED_AT)
    )
  );
  const latestApproval = approvalRows[0];
  const commitRows = [...(commits.data ?? [])]
    .filter(isLocalCommit)
    .sort((left, right) =>
      String(right.createdAt || right.CREATED_AT).localeCompare(
        String(left.createdAt || left.CREATED_AT)
      )
    );
  const latestCommit = commitRows[0];
  const deliveryRows = [...(deliveries.data ?? [])].sort((left, right) =>
    String(right.createdAt || right.CREATED_AT).localeCompare(
      String(left.createdAt || left.CREATED_AT)
    )
  );
  const latestDelivery = deliveryRows[0];

  const hasDiff = Boolean(changes) || untrackedRows.length > 0;
  const diffReviewed = Boolean(latestApproval) || !hasDiff;
  const approvalStatus = latestApproval?.STATUS || latestApproval?.status;
  const approved = approvalStatus === 'APPROVED';
  const committed = Boolean(latestCommit);
  const consumedByCommit = approvalStatus === 'STALE' && !hasDiff && committed;
  const approvalInvalid =
    approvalStatus === 'REJECTED' || (approvalStatus === 'STALE' && !consumedByCommit);
  const deliveryStatus = latestDelivery?.status || latestDelivery?.STATUS;
  const deliveryFailed = ['CONFLICT', 'UNKNOWN', 'REJECTED'].includes(deliveryStatus);

  const activeStep = deliveryActiveStep({
    diffReviewed,
    approvalStatus,
    committed,
    deliveryStatus,
    hasDiff
  });

  const steps = [
    {
      label: '差异核对',
      detail: hasDiff
        ? latestApproval
          ? `已基于差异创建审查 #${shortId(latestApproval.ID)}`
          : '存在未审批的工作区差异'
        : '工作区没有待审查差异'
    },
    {
      label: '审查批准',
      detail: consumedByCommit
        ? `已通过并用于提交 · ${dateTime(latestApproval.UPDATED_AT || latestApproval.CREATED_AT)}`
        : latestApproval
          ? `${approvalLabels[approvalStatus] || approvalStatus} · ${dateTime(latestApproval.UPDATED_AT || latestApproval.CREATED_AT)}`
          : '还没有审查记录',
      error: approvalInvalid
    },
    {
      label: '本地提交',
      detail: latestCommit
        ? `LOCAL_COMMIT · ${shortId(latestCommit.commitId || latestCommit.ID || latestCommit.id)}`
        : approved
          ? '已批准，可创建本地提交'
          : approvalInvalid
            ? '审批已失效，需重新审查'
            : '需要先获得有效批准'
    },
    {
      label: '推送 / 合并',
      detail: latestDelivery
        ? `${latestDelivery.kind || latestDelivery.KIND} · ${
            deliveryLabels[deliveryStatus] || deliveryStatus
          }`
        : committed
          ? '需要时可准备推送或合并'
          : '完成本地提交后再决定',
      error: deliveryFailed,
      optional: true
    }
  ];

  return (
    <Box
      sx={{
        border: `1px solid ${googleTokens.outline}`,
        borderRadius: 3,
        px: { xs: 1.5, sm: 2.5 },
        py: 2,
        bgcolor: '#FBFCFD'
      }}
    >
      <Typography sx={{ fontSize: 13.5, fontWeight: 600 }}>Git 交付进度</Typography>
      <Typography variant="caption" color="text.secondary">
        仅按服务端记录推导；当前在独立隔离分支
        {status.data?.branch ? `（${status.data.branch}）` : ''}提交，推送 / 合并是可选的最后一步。
      </Typography>
      {approvalInvalid && (
        <Typography variant="body2" color="error.main" sx={{ mt: 0.75 }}>
          当前差异的审批已失效或被拒绝，请重新发起审查；此前本地提交或推送记录不会被视为当前差异的有效证据。
        </Typography>
      )}
      <Stepper
        activeStep={activeStep}
        orientation={narrow ? 'vertical' : 'horizontal'}
        alternativeLabel={!narrow}
        sx={{ mt: 1.5 }}
      >
        {steps.map(step => (
          <Step key={step.label}>
            <StepLabel error={step.error}>
              <Typography sx={{ fontSize: 12.5, fontWeight: 500 }}>
                {step.label}
                {step.optional ? '（可选）' : ''}
              </Typography>
              <Typography
                variant="caption"
                color="text.secondary"
                sx={{ display: 'block', maxWidth: 240, lineHeight: 1.45 }}
              >
                {step.detail}
              </Typography>
            </StepLabel>
          </Step>
        ))}
      </Stepper>
    </Box>
  );
}
