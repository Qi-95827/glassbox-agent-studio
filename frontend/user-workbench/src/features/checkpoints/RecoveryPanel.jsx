import React from 'react';
import { Button } from 'antd';
import { ReloadOutlined } from '@ant-design/icons';

/** 展示可申请的安全边界；最终恢复条件由服务端再次校验。 */
export default function RecoveryPanel({ run, task, checkpoints, runs, onRecover, clock }) {
  if (run.STATUS !== 'INTERRUPTED') return null;
  const workspaceAllowed = Boolean(task.WORKSPACE_ID) && !task.WRITE_ENABLED && !task.BUG_ID && !task.MEMORY_ENABLED;
  const restricted = Boolean(task.BUG_ID || task.WRITE_ENABLED || (task.WORKSPACE_ID && !workspaceAllowed));
  const alreadyRequested = runs.some((child) => child.PARENT_ID === run.ID
    && !(['REJECTED', 'PRECONDITION_FAILED'].includes(child.RECOVERY_DISPATCH) && child.STATUS === 'FAILED'));
  const explanation = alreadyRequested
    ? '此运行已经发起恢复，请查看关联的恢复运行。'
    : restricted
    ? '当前写入、Bug 或启用项目背景的工作区任务暂不支持恢复，请先检查外部状态。'
    : workspaceAllowed
      ? `只读工作区仅支持分析完成后的检查点；申请和开始恢复时会重新核对工作区状态。${checkpoints.some((item) => item.VERSION === 6 && item.STAGE === 'BEFORE_REVIEW') ? '' : ' 暂无该类检查点，请查看执行轨迹了解详情。'}`
      : '选择一个有效的检查点继续。';

  function canRequest(checkpoint) {
    if (!checkpoint.VALID || restricted || alreadyRequested) return false;
    return workspaceAllowed ? checkpoint.VERSION === 6 && checkpoint.STAGE === 'BEFORE_REVIEW' : checkpoint.VERSION !== 6;
  }

  return (
    <div className="recovery-box">
      <strong>恢复运行</strong>
      <p>{explanation}{task.MODEL_MODE === 'deepseek' ? ' 恢复可能再次调用 DeepSeek 并产生费用。' : ''}</p>
      {checkpoints.length === 0 && <span>暂无可用检查点</span>}
      {checkpoints.map((checkpoint) => {
        const available = canRequest(checkpoint);
        const stage = checkpoint.STAGE === 'BEFORE_TOOL'
          ? 'Agent 启动前'
          : checkpoint.STAGE === 'BEFORE_REVIEW' ? '分析完成后、审查前' : '旧版工具节点';
        return (
          <Button key={checkpoint.ID} size="small" disabled={!available}
            onClick={() => onRecover(checkpoint.ID)} icon={<ReloadOutlined />}>
            {stage} · {clock(checkpoint.CREATED_AT)}{available ? '' : '（不可用）'}
          </Button>
        );
      })}
    </div>
  );
}
