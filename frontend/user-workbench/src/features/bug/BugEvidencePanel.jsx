import React, { useEffect, useState } from 'react';
import { App, Button } from 'antd';

const observationLabels = {
  FAILURE_OBSERVED: '曾观察到测试失败',
  FAILURE_NOT_OBSERVED: '未观察到测试失败',
  CONFLICT: '相同代码状态的观察结果冲突',
  UNCONFIRMED: '证据不足',
  NOT_APPLICABLE: '不适用',
};

const regressionLabels = {
  PASSED_CURRENT_VERSION: '有通过记录',
  FAILED_CURRENT_VERSION: '有失败记录',
  CONFLICT: '通过与失败记录冲突',
  UNCONFIRMED: '没有足够证据',
  NOT_APPLICABLE: '不适用',
};

/** 展示当前运行的测试记录，并按用户请求评估当时的代码状态。 */
export default function BugEvidencePanel({ runId, checks, api }) {
  const { message } = App.useApp();
  const [assessment, setAssessment] = useState(null);
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    setAssessment(null);
  }, [runId]);

  async function assessEvidence() {
    if (!runId) return;
    setLoading(true);
    try {
      const result = await api(`/evaluations/bug-evidence?runId=${encodeURIComponent(runId)}`);
      setAssessment(result);
    } catch (error) {
      message.error(error.message);
    } finally {
      setLoading(false);
    }
  }

  return (
    <>
      <div className="section-row">
        <h3>本次 Bug 检测证据</h3>
        <span>{checks.length} 条</span>
      </div>
      <Button onClick={assessEvidence} loading={loading}>评估当前证据</Button>
      {assessment?.runId === runId && (
        <div className="result-box">
          <strong>证据快照</strong>
          <p>失败观察：{observationLabels[assessment.observation] || '未知'}</p>
          <p>当前代码版本回归：{regressionLabels[assessment.regression] || '未知'}</p>
          <p>
            这是点击时的快照，不证明特定 Bug 已复现或修复；代码变化后请重新评估。
            观察证据 {assessment.observationEvidenceIds.length} 条，当前版本回归证据{' '}
            {assessment.regressionEvidenceIds.length} 条；归属或格式异常而忽略{' '}
            {assessment.ignoredEvidenceCount} 条。
          </p>
        </div>
      )}
      <p className="hint">
        仅显示明确关联本次运行的检测；历史手动检测不会自动归属。
        测试结果需要结合对应代码版本及人工审查。
      </p>
      {checks.length ? checks.map((check) => (
        <details className="agent-card handoff" key={check.ID}>
          <summary>
            {({ REPRODUCE: '失败观察', REGRESSION: '回归检查' })[check.KIND] || '未知检测类型'} · {check.STATUS} ·{' '}
            {check.DURATION_MS} 毫秒
          </summary>
          <p>证据 ID：{check.ID}；代码状态摘要：{check.WORKSPACE_FINGERPRINT || '未知'}</p>
          {check.OUTPUT && <pre className="workspace-output">{check.OUTPUT}</pre>}
        </details>
      )) : <p className="hint">本次运行没有可确认归属的检测记录。</p>}
    </>
  );
}
