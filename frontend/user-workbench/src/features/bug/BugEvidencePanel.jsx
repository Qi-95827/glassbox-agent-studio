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
  STALE: '代码版本已变化',
};

const caseResultLabels = {
  PASSED: '通过',
  FAILED: '失败',
  ERROR: '错误',
  SKIPPED: '跳过',
  EXPECTED_FAILURE: '预期失败',
  UNEXPECTED_SUCCESS: '意外通过',
};

/** 展示当前运行的测试记录，并按用户请求评估当时的代码状态。 */
export default function BugEvidencePanel({ runId, checks, api }) {
  const { message } = App.useApp();
  const [assessment, setAssessment] = useState(null);
  const [loading, setLoading] = useState(false);
  const [bug, setBug] = useState(null);
  const [delivery, setDelivery] = useState(null);

  useEffect(() => {
    setAssessment(null);
    setDelivery(null);
  }, [runId]);

  useEffect(() => {
    let active = true;
    if (!runId) return undefined;
    api(`/bugs/delivery?runId=${encodeURIComponent(runId)}`)
      .then((result) => {
        if (active) setDelivery(result);
      })
      .catch(() => {});
    return () => {
      active = false;
    };
  }, [api, runId]);

  useEffect(() => {
    let active = true;
    api('/bugs')
      .then((list) => {
        if (active && Array.isArray(list) && list.length) setBug(list[0]);
      })
      .catch(() => {});
    return () => {
      active = false;
    };
  }, [api]);

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
      {bug && (
        <div className="result-box">
          <strong>目标现象</strong>
          <p>来源：{bug.source} v{bug.version}</p>
          <p>{bug.expectedSymptom}</p>
          <p>预期失败测试：{bug.expectedFailingTest}；预期失败信息：{bug.expectedFailureText}</p>
        </div>
      )}
      {delivery?.intake && (
        <div className="result-box">
          <strong>Bug 分诊与交付</strong>
          <p>原始描述：{delivery.intake.originalDescription}</p>
          <p>
            来源：{delivery.intake.source} v{delivery.intake.sourceVersion} · 提交{' '}
            {delivery.intake.createdAt} · 第 {delivery.intake.submissionSeq} 次
          </p>
          {delivery.intake.duplicateOf ? (
            <p>同来源同内容重复提交；首次记录：{delivery.intake.duplicateOf}</p>
          ) : delivery.intake.previousIntakeId ? (
            <p>同来源内容已变化；上一记录：{delivery.intake.previousIntakeId}</p>
          ) : (
            <p>该来源的首次提交。</p>
          )}
          <p>
            分诊：{delivery.triage?.label || '待分诊'}；{delivery.triage?.reason || '尚未完成复现检查'}
          </p>
          <p>
            交付结论：{delivery.conclusionLabel}；{delivery.conclusionReason}
          </p>
          {delivery.regressionEvidenceCurrent === false && (
            <p>
              回归记录代码状态：{delivery.regressionFingerprint || '未记录'}；当前代码状态：
              {delivery.currentFingerprint || '无法读取'}。
            </p>
          )}
        </div>
      )}
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
            {({ REPRODUCE: '失败观察', REGRESSION: '回归检查' })[check.KIND] || '未知检测类型'} · {checkStatusLabels[check.STATUS] || check.STATUS} ·{' '}
            {check.DURATION_MS} 毫秒
          </summary>
          <p>证据 ID：{check.ID}；代码状态摘要：{check.WORKSPACE_FINGERPRINT || '未知'}</p>
          {Array.isArray(check.CASES) && check.CASES.length > 0 && (
            <div className="result-box">
              <strong>逐用例结果</strong>
              <ul>
                {check.CASES.map((test) => (
                  <li key={`${check.ID}-${test.SEQUENCE_NO}`}>
                    <code>{test.TEST_ID}</code> · {caseResultLabels[test.RESULT] || test.RESULT}
                    {test.MESSAGE ? `：${test.MESSAGE}` : ''}
                  </li>
                ))}
              </ul>
            </div>
          )}
          {check.OUTPUT && <pre className="workspace-output">{check.OUTPUT}</pre>}
        </details>
      )) : <p className="hint">本次运行没有可确认归属的检测记录。</p>}
    </>
  );
}
