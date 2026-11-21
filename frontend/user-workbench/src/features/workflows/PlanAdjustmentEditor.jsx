import { useState } from 'react';
import {
  Alert,
  Box,
  Button,
  Checkbox,
  ListItemText,
  MenuItem,
  Stack,
  TextField,
  Typography
} from '@mui/material';
import { runsApi } from '../../api/runs';

/** 待确认计划的预算与依赖调整；应用后仍需再次确认计划才执行。 */
export default function PlanAdjustmentEditor({ detail }) {
  const nodes = detail?.planNodes ?? [];
  const plan = detail?.workflowPlan;
  const [budgets, setBudgets] = useState(() =>
    Object.fromEntries(
      nodes.map(node => [
        node.key,
        {
          maxModelCalls: node.budget?.maxModelCalls ?? '',
          maxToolCalls: node.budget?.maxToolCalls ?? ''
        }
      ])
    )
  );
  const [dependencies, setDependencies] = useState(() =>
    Object.fromEntries(
      nodes.map(node => [
        node.key,
        (node.dependencyIds ?? [])
          .map(id => nodes.find(item => item.id === id)?.key)
          .filter(Boolean)
      ])
    )
  );
  const [proposal, setProposal] = useState(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);

  if (!plan || !plan.reviewRequired || plan.approvedAt || detail?.run?.STATUS !== 'PENDING') {
    return null;
  }

  const propose = async () => {
    setBusy(true);
    setError(null);
    try {
      const changes = nodes.map(node => ({
        key: node.key,
        maxModelCalls:
          budgets[node.key]?.maxModelCalls === '' ? null : Number(budgets[node.key].maxModelCalls),
        maxToolCalls:
          budgets[node.key]?.maxToolCalls === '' ? null : Number(budgets[node.key].maxToolCalls),
        dependencies: dependencies[node.key] || []
      }));
      const saved = await runsApi.planProposal(detail.run.ID, {
        changes,
        expectedBaseSha256: plan.graphSha256
      });
      setProposal(saved);
    } catch (failure) {
      setError(failure.message);
    } finally {
      setBusy(false);
    }
  };

  const apply = async () => {
    if (!proposal) return;
    setBusy(true);
    try {
      await runsApi.applyPlanProposal(detail.run.ID, proposal.id);
      setProposal(null);
      setError(null);
    } catch (failure) {
      setError(failure.message);
    } finally {
      setBusy(false);
    }
  };

  return (
    <Box
      sx={{
        bgcolor: '#FFFFFF',
        border: '1px solid',
        borderColor: 'divider',
        borderRadius: 4,
        p: { xs: 2, sm: 2.5 }
      }}
    >
      <Typography sx={{ fontSize: 15, fontWeight: 500 }}>调整候选计划</Typography>
      <Typography variant="body2" color="text.secondary" sx={{ mt: 0.4, mb: 1.5 }}>
        只能调整节点预算和依赖；节点身份、授权与 Agent
        定义仍由服务端校验。应用后仍需确认计划才执行。
      </Typography>
      <Stack spacing={1.2}>
        {nodes.map(node => (
          <Stack
            key={node.key}
            direction={{ xs: 'column', md: 'row' }}
            spacing={1}
            sx={{ alignItems: { md: 'center' } }}
          >
            <Typography variant="body2" sx={{ minWidth: 170 }}>
              {node.label || node.key}
            </Typography>
            <TextField
              size="small"
              type="number"
              label="模型上限"
              value={budgets[node.key]?.maxModelCalls ?? ''}
              onChange={event =>
                setBudgets(current => ({
                  ...current,
                  [node.key]: { ...current[node.key], maxModelCalls: event.target.value }
                }))
              }
              slotProps={{ htmlInput: { min: 1, max: 20 } }}
              sx={{ width: 130 }}
            />

            <TextField
              size="small"
              type="number"
              label="工具上限"
              value={budgets[node.key]?.maxToolCalls ?? ''}
              onChange={event =>
                setBudgets(current => ({
                  ...current,
                  [node.key]: { ...current[node.key], maxToolCalls: event.target.value }
                }))
              }
              slotProps={{ htmlInput: { min: 0, max: 20 } }}
              sx={{ width: 130 }}
            />

            <TextField
              select
              size="small"
              label="前置依赖"
              value={dependencies[node.key] || []}
              onChange={event =>
                setDependencies(current => ({ ...current, [node.key]: event.target.value }))
              }
              slotProps={{ select: { multiple: true, renderValue: value => value.join('、') } }}
              sx={{ flex: 1, minWidth: 200 }}
            >
              {nodes
                .filter(item => item.key !== node.key)
                .map(item => (
                  <MenuItem key={item.key} value={item.key}>
                    <Checkbox
                      size="small"
                      checked={(dependencies[node.key] || []).includes(item.key)}
                    />

                    <ListItemText primary={item.label || item.key} />
                  </MenuItem>
                ))}
            </TextField>
          </Stack>
        ))}
      </Stack>
      <Button sx={{ mt: 1.5 }} variant="outlined" loading={busy} onClick={propose}>
        校验并保存候选
      </Button>
      {error && (
        <Alert severity="error" sx={{ mt: 1 }}>
          调整未通过：{error}
        </Alert>
      )}
      {proposal && (
        <Box sx={{ mt: 1.5 }}>
          <Typography variant="body2">
            候选 v{proposal.version} · {proposal.proposalSha256?.slice(0, 16)}…
          </Typography>
          {proposal.diff?.length === 0 ? (
            <Typography variant="caption" color="text.secondary">
              与当前计划没有字段差异。
            </Typography>
          ) : (
            proposal.diff?.map(change => (
              <Typography
                key={change.key}
                variant="caption"
                color="text.secondary"
                sx={{ display: 'block' }}
              >
                {change.label}：模型 {change.beforeMaxModelCalls ?? '未设置'} →{' '}
                {change.afterMaxModelCalls ?? '未设置'}；工具{' '}
                {change.beforeMaxToolCalls ?? '未设置'} → {change.afterMaxToolCalls ?? '未设置'}
              </Typography>
            ))
          )}
          <Button variant="contained" loading={busy} onClick={apply} sx={{ mt: 1 }}>
            应用候选并保留版本
          </Button>
        </Box>
      )}
    </Box>
  );
}
