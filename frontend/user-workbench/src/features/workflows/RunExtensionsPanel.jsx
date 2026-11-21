import { useState } from 'react';
import { Alert, Box, Button, Chip, MenuItem, Stack, TextField, Typography } from '@mui/material';
import { TraceSection, Readout } from '../../components/WorkbenchDialog';
import { runsApi } from '../../api/runs';
import { dateTime, shortId } from '../../utils/format';

/** 执行页上的来源、授权、快照、费用和外部动作证据。 */
export default function RunExtensionsPanel({ detail }) {
  const [actions, setActions] = useState(detail?.externalActions ?? []);
  const [sources, setSources] = useState(detail?.sourceArtifacts ?? []);
  const [scopeState, setScopeState] = useState(detail?.workspaceScope);
  const [sourceRun, setSourceRun] = useState('');
  const [sourceKind, setSourceKind] = useState('DOCUMENT');
  const [sourceVersion, setSourceVersion] = useState('');
  const [targetVersion, setTargetVersion] = useState(detail?.task?.VERSION ?? '');
  const [error, setError] = useState(null);

  const scope = detail?.workspaceScope;
  const snapshots = detail?.snapshots ?? [];
  const lease = detail?.recoveryLease;
  const cost = detail?.costEstimate;
  const route = detail?.entryRoute;

  const revoke = async directory => {
    try {
      const updated = await runsApi.revokeScope(detail.run.ID, directory);
      setScopeState(current => ({
        ...current,
        DIRECTORY_SCOPES: updated.directories,
        EFFECTIVE_FILES: updated.effectiveFiles
      }));
      setError(null);
    } catch (failure) {
      setError(failure.message);
    }
  };

  const attachSource = async () => {
    try {
      const attached = await runsApi.sourceArtifacts(detail.run.ID, {
        sourceRunId: sourceRun.trim(),
        kind: sourceKind,
        expectedTargetTaskVersion: targetVersion === '' ? null : Number(targetVersion),
        expectedSourceTaskVersion: sourceVersion === '' ? null : Number(sourceVersion)
      });
      setSources(rows => [...rows.filter(row => row.id !== attached.id), attached]);
      setError(null);
    } catch (failure) {
      setError(failure.message);
    }
  };

  const reconcile = async id => {
    try {
      const updated = await runsApi.reconcileExternalAction(id);
      setActions(rows => rows.map(row => (row.id === id ? updated : row)));
    } catch (failure) {
      setActions(rows =>
        rows.map(row => (row.id === id ? { ...row, resultEvidence: failure.message } : row))
      );
    }
  };

  return (
    <Stack spacing={1.5}>
      {error && <Alert severity="warning">操作未通过：{error}</Alert>}
      {route && (
        <Box>
          <Typography sx={{ fontSize: 14, fontWeight: 600, mb: 0.5 }}>
            入口路由 · {route.RULE_CODE}
          </Typography>
          <Typography variant="body2" color="text.secondary">
            实际模板：{route.TEMPLATE_ID || '兼容流程'}；入口：{route.ROUTED_KIND}
          </Typography>
          <Typography variant="caption" color="text.secondary">
            决策摘要：{route.DECISION_SHA256?.slice(0, 16)}…；路由只解释选择，不授予权限。
          </Typography>
        </Box>
      )}

      {(sources.length > 0 || scope) && (
        <TraceSection title="授权范围与既有成果" defaultExpanded>
          <Stack spacing={1}>
            {scope && (
              <>
                <Typography variant="body2">
                  目录：
                  {(scopeState?.DIRECTORY_SCOPES || scope.DIRECTORY_SCOPES || []).join('、') ||
                    '无'}
                </Typography>
                <Typography variant="caption" color="text.secondary">
                  有效文件 {(scopeState?.EFFECTIVE_FILES || scope.EFFECTIVE_FILES)?.length ?? 0}{' '}
                  个； 范围摘要 {scope.SCOPE_SHA256?.slice(0, 16)}…
                </Typography>
                <Stack direction="row" spacing={0.75} sx={{ flexWrap: 'wrap' }}>
                  {(scopeState?.DIRECTORY_SCOPES || []).map(directory => (
                    <Button
                      key={directory}
                      size="small"
                      variant="outlined"
                      onClick={() => revoke(directory)}
                    >
                      撤销 {directory}
                    </Button>
                  ))}
                </Stack>
              </>
            )}
            {sources.map(source => (
              <Typography key={source.id} variant="body2">
                {source.kind} · {source.summary}
              </Typography>
            ))}
            {sources.length === 0 && (
              <Typography variant="body2" color="text.secondary">
                尚未接入既有成果。
              </Typography>
            )}
            {detail.run.STATUS === 'PENDING' && (
              <Stack spacing={1} sx={{ pt: 0.5 }}>
                <Typography variant="caption" color="text.secondary">
                  接入既有成果（来源运行须已结算）
                </Typography>
                <Stack direction="row" spacing={1} sx={{ flexWrap: 'wrap' }}>
                  <TextField
                    size="small"
                    label="来源 runId"
                    value={sourceRun}
                    onChange={event => setSourceRun(event.target.value)}
                    sx={{ width: 210 }}
                  />

                  <TextField
                    select
                    size="small"
                    label="成果类型"
                    value={sourceKind}
                    onChange={event => setSourceKind(event.target.value)}
                    sx={{ width: 140 }}
                  >
                    <MenuItem value="DOCUMENT">文档</MenuItem>
                    <MenuItem value="CODE_DIFF">代码差异</MenuItem>
                    <MenuItem value="TEST_EVIDENCE">测试成果</MenuItem>
                  </TextField>
                  <TextField
                    size="small"
                    type="number"
                    label="来源版本"
                    value={sourceVersion}
                    onChange={event => setSourceVersion(event.target.value)}
                    sx={{ width: 110 }}
                  />

                  <TextField
                    size="small"
                    type="number"
                    label="目标版本"
                    value={targetVersion}
                    onChange={event => setTargetVersion(event.target.value)}
                    sx={{ width: 110 }}
                  />

                  <Button
                    variant="outlined"
                    onClick={attachSource}
                    disabled={!sourceRun.trim() || sourceVersion === '' || targetVersion === ''}
                  >
                    接入
                  </Button>
                </Stack>
              </Stack>
            )}
          </Stack>
        </TraceSection>
      )}

      <TraceSection title="周期快照与恢复租约">
        {snapshots.length === 0 ? (
          <Typography variant="body2" color="text.secondary">
            尚无周期快照。
          </Typography>
        ) : (
          snapshots.map(snapshot => (
            <Typography key={snapshot.ID} variant="body2">
              事件位置 {snapshot.EVENT_SEQ} · 已完成 {snapshot.COMPLETED_NODES} 步 · 轮次{' '}
              {snapshot.ROUNDS} · 工具 {snapshot.TOOLS}
            </Typography>
          ))
        )}
        {lease && (
          <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mt: 0.75 }}>
            恢复租约：{lease.STATUS} · {lease.OWNER} · 到期 {dateTime(lease.EXPIRES_AT)}
          </Typography>
        )}
      </TraceSection>

      <TraceSection title="费用估算与硬预算">
        {cost ? (
          <Stack spacing={0.5}>
            <Typography variant="body2">
              上限 {cost.MAX_COST_MICROS} {cost.CURRENCY} · 已预留 {cost.RESERVED_MICROS} · 已结算{' '}
              {cost.SETTLED_MICROS} · 未知调用 {cost.UNKNOWN_CALLS}
            </Typography>
            {cost.OVER_BUDGET && (
              <Typography variant="body2" color="warning.dark">
                已超支 {cost.OVERAGE_MICROS}；账本保留实际结算，后续调用已停止。
              </Typography>
            )}
            <Typography variant="caption" color="text.secondary">
              价格版本 {cost.PRICE_PROVIDER}/{cost.PRICE_MODEL} v{cost.PRICE_VERSION}
              ；单次可计价上界 {cost.PER_CALL_UPPER_BOUND_MICROS}。未知用量不会被当作零。
            </Typography>
          </Stack>
        ) : (
          <Typography variant="body2" color="text.secondary">
            该运行没有绑定费用硬预算；当前只有 Token 和耗时统计。
          </Typography>
        )}
      </TraceSection>

      <TraceSection title="外部动作对账" subtitle={`${actions.length} 条`}>
        {actions.length === 0 ? (
          <Typography variant="body2" color="text.secondary">
            没有登记外部动作。
          </Typography>
        ) : (
          <Stack spacing={1.2}>
            {actions.map(action => (
              <Box key={action.id}>
                <Stack
                  direction="row"
                  spacing={0.75}
                  sx={{ alignItems: 'center', flexWrap: 'wrap' }}
                >
                  <Typography variant="body2" sx={{ fontWeight: 600 }}>
                    {action.kind}
                  </Typography>
                  <Chip size="small" label={action.status} variant="outlined" />
                  <Typography variant="caption" color="text.secondary">
                    {action.target} · 目标版本 {action.targetVersion || '未记录'}
                  </Typography>
                </Stack>
                <Typography variant="caption" color="text.secondary" sx={{ display: 'block' }}>
                  尝试 {action.attempts} 次；{action.resultEvidence || '尚无查询证据'}
                </Typography>
                {(action.checks || []).map(check => (
                  <Typography
                    key={check.id}
                    variant="caption"
                    color="text.secondary"
                    sx={{ display: 'block' }}
                  >
                    核对 {check.outcome} · {check.evidence || '无证据'} ·{' '}
                    {dateTime(check.checkedAt)}
                  </Typography>
                ))}
                {action.needsManualReview && action.status !== 'SUCCEEDED' && (
                  <Button size="small" onClick={() => reconcile(action.id)}>
                    查询对账
                  </Button>
                )}
              </Box>
            ))}
          </Stack>
        )}
        <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mt: 0.75 }}>
          对账只查询并补记证据，不重放外部写入；未知结果保持待人工核对。
        </Typography>
      </TraceSection>

      {detail.externalActions?.length > 0 && (
        <TraceSection title="外部动作原始记录" dense>
          <Readout mono>{JSON.stringify(detail.externalActions, null, 2)}</Readout>
          <Typography variant="caption" color="text.secondary">
            运行 {shortId(detail.run.ID)}
          </Typography>
        </TraceSection>
      )}
    </Stack>
  );
}
