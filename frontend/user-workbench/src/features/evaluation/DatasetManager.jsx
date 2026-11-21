import { useEffect, useState } from 'react';
import {
  Alert,
  Box,
  Button,
  Checkbox,
  FormControlLabel,
  MenuItem,
  Stack,
  TextField,
  Typography
} from '@mui/material';
import { evaluationApi } from '../../api/evaluations';
import { googleTokens } from '../../theme/googleTheme';

const blank = () => ({
  code: `CASE_${Math.random().toString(36).slice(2, 6).toUpperCase()}`,
  title: '新样本',
  goal: '描述该样本要验证的行为',
  scenario: 'normal',
  expectedStatus: 'COMPLETED',
  maxRounds: 4,
  maxTools: 2,
  sourceKind: 'MANUAL',
  sourceRef: null
});

/** 可管理评测数据集：编辑草稿、发布不可变版本、从失败运行录入并回看批次。 */
export default function DatasetManager({ onOpenRun }) {
  const [key, setKey] = useState('MANAGED_UI');
  const [rows, setRows] = useState([]);
  const [versions, setVersions] = useState([]);
  const [batches, setBatches] = useState([]);
  const [version, setVersion] = useState(null);
  const [capture, setCapture] = useState({ runId: '', confirmed: false });
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);

  const failureRun = capture.runId;
  const confirmed = capture.confirmed;
  const setFailureRun = value => setCapture(previous => ({ ...previous, runId: value }));
  const setConfirmed = value => setCapture(previous => ({ ...previous, confirmed: value }));

  const load = async currentKey => {
    try {
      const [draft, published, batchList] = await Promise.all([
        evaluationApi.dataset(currentKey),
        evaluationApi.datasetVersions(currentKey),
        evaluationApi.datasetBatches()
      ]);
      setRows(draft);
      setVersions(published);
      setBatches(batchList);
      setVersion(previous => previous ?? published[0]?.version ?? null);
      setError(null);
    } catch (failure) {
      setError(failure.message);
    }
  };

  useEffect(() => {
    setVersion(null);
    load(key);
  }, [key]);

  const update = (code, field, value) =>
    setRows(current => current.map(row => (row.code === code ? { ...row, [field]: value } : row)));

  const save = async row => {
    setBusy(true);
    try {
      await evaluationApi.saveSample(key, row.code, {
        code: row.code,
        title: row.title,
        goal: row.goal,
        scenario: row.scenario,
        expectedStatus: row.expectedStatus,
        maxRounds: Number(row.maxRounds) || 1,
        maxTools: Number(row.maxTools) || 0,
        sourceKind: row.sourceKind || 'MANUAL',
        sourceRef: row.sourceRef || null
      });
      await load(key);
    } catch (failure) {
      setError(failure.message);
    } finally {
      setBusy(false);
    }
  };

  const publish = async () => {
    setBusy(true);
    try {
      await evaluationApi.publishDataset(key);
      await load(key);
    } catch (failure) {
      setError(failure.message);
    } finally {
      setBusy(false);
    }
  };

  const addFromRun = async () => {
    setBusy(true);
    try {
      await evaluationApi.datasetFromRun(key, {
        runId: failureRun.trim(),
        confirmedRedaction: confirmed
      });
      setFailureRun('');
      setConfirmed(false);
      await load(key);
    } catch (failure) {
      setError(failure.message);
    } finally {
      setBusy(false);
    }
  };

  const startBatch = async () => {
    setBusy(true);
    try {
      await evaluationApi.startDatasetBatch(key, version);
      await load(key);
    } catch (failure) {
      setError(failure.message);
    } finally {
      setBusy(false);
    }
  };

  return (
    <Stack spacing={2}>
      <Alert severity="info">
        草稿可编辑；发布后版本不可变。批次绑定实际版本、配置和预算，失败样本保留运行入口。
      </Alert>
      <Stack
        direction={{ xs: 'column', sm: 'row' }}
        spacing={1.25}
        useFlexGap
        sx={{ flexWrap: 'wrap' }}
      >
        <TextField
          size="small"
          label="数据集编码"
          value={key}
          onChange={event => setKey(event.target.value.toUpperCase())}
          sx={{ width: 190 }}
        />

        <Button variant="outlined" loading={busy} onClick={publish}>
          发布当前草稿
        </Button>
        <TextField
          select
          size="small"
          label="评测版本"
          value={version ?? ''}
          onChange={event => setVersion(event.target.value)}
          sx={{ width: 170 }}
        >
          {versions.map(item => (
            <MenuItem key={item.version} value={item.version}>
              v{item.version} · {item.sampleCount} 样本
            </MenuItem>
          ))}
        </TextField>
        <Button variant="contained" loading={busy} disabled={!versions.length} onClick={startBatch}>
          运行数据集批次
        </Button>
      </Stack>
      {error && <Alert severity="error">{error}</Alert>}

      <Stack spacing={1.25}>
        {rows.map(row => (
          <Box
            key={row.code}
            sx={{ p: 1.75, border: `1px solid ${googleTokens.outline}`, borderRadius: 3 }}
          >
            <Stack spacing={1}>
              <TextField
                size="small"
                label={`${row.code} 标题`}
                value={row.title}
                onChange={event => update(row.code, 'title', event.target.value)}
              />

              <TextField
                multiline
                minRows={2}
                label="样本目标"
                value={row.goal}
                onChange={event => update(row.code, 'goal', event.target.value)}
              />

              <Stack direction="row" spacing={1} useFlexGap sx={{ flexWrap: 'wrap' }}>
                <TextField
                  select
                  size="small"
                  label="场景"
                  value={row.scenario}
                  onChange={event => update(row.code, 'scenario', event.target.value)}
                  sx={{ width: 120 }}
                >
                  <MenuItem value="normal">正常</MenuItem>
                  <MenuItem value="fail">失败</MenuItem>
                  <MenuItem value="repeat">重复调用</MenuItem>
                </TextField>
                <TextField
                  select
                  size="small"
                  label="预期状态"
                  value={row.expectedStatus}
                  onChange={event => update(row.code, 'expectedStatus', event.target.value)}
                  sx={{ width: 140 }}
                >
                  {['COMPLETED', 'FAILED', 'LIMITED', 'BLOCKED'].map(value => (
                    <MenuItem key={value} value={value}>
                      {value}
                    </MenuItem>
                  ))}
                </TextField>
                <TextField
                  size="small"
                  type="number"
                  label="轮次"
                  value={row.maxRounds}
                  onChange={event => update(row.code, 'maxRounds', event.target.value)}
                  sx={{ width: 90 }}
                />

                <TextField
                  size="small"
                  type="number"
                  label="工具"
                  value={row.maxTools}
                  onChange={event => update(row.code, 'maxTools', event.target.value)}
                  sx={{ width: 90 }}
                />

                <Button
                  size="small"
                  variant="outlined"
                  loading={busy}
                  onClick={() => save(row)}
                  sx={{ alignSelf: 'center' }}
                >
                  保存草稿
                </Button>
              </Stack>
              <Typography variant="caption" color="text.secondary">
                草稿 v{row.version} · {row.sourceKind}
                {row.sourceRef ? ` · ${row.sourceRef}` : ''} · 已脱敏 {row.redacted ? '是' : '否'}
              </Typography>
            </Stack>
          </Box>
        ))}
      </Stack>
      <Button
        variant="outlined"
        onClick={() =>
          setRows(current => [...current, { ...blank(), version: 0, redacted: false }])
        }
        sx={{ alignSelf: 'flex-start' }}
      >
        新增样本
      </Button>

      <Box>
        <Typography sx={{ fontSize: 14, fontWeight: 600 }}>失败案例录入</Typography>
        <Typography variant="caption" color="text.secondary">
          需确认已按页面规则脱敏；录入的是可编辑草稿，不会自动发布。
        </Typography>
        <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1} sx={{ mt: 1 }}>
          <TextField
            size="small"
            label="失败运行的 runId"
            value={failureRun}
            onChange={event => setFailureRun(event.target.value)}
            sx={{ flex: 1 }}
          />

          <Button
            variant="outlined"
            loading={busy}
            disabled={!failureRun.trim() || !confirmed}
            onClick={addFromRun}
          >
            从失败运行录入
          </Button>
        </Stack>
        <FormControlLabel
          control={
            <Checkbox
              size="small"
              checked={confirmed}
              onChange={event => setConfirmed(event.target.checked)}
            />
          }
          label={<Typography variant="body2">确认已脱敏并同意录入草稿</Typography>}
        />
      </Box>

      <Box>
        <Typography sx={{ fontSize: 14, fontWeight: 600, mb: 0.75 }}>可管理批次</Typography>
        {batches.length === 0 && (
          <Typography variant="body2" color="text.secondary">
            尚无批次。
          </Typography>
        )}
        <Stack spacing={1}>
          {batches.map(batch => (
            <Stack
              key={batch.id}
              direction={{ xs: 'column', sm: 'row' }}
              spacing={1}

              sx={{
                p: 1.25,
                border: `1px solid ${googleTokens.outline}`,
                borderRadius: 2.5,
                alignItems: { sm: 'center' }
              }}
            >
              <Box sx={{ flex: 1, minWidth: 0 }}>
                <Typography variant="body2" sx={{ fontWeight: 500 }}>
                  {batch.datasetKey} · {batch.status}
                </Typography>
                <Typography variant="caption" color="text.secondary">
                  版本 {batch.datasetVersionId?.slice(0, 8)} · 定义摘要{' '}
                  {batch.definitionSha256?.slice(0, 16)}…
                </Typography>
                <Typography variant="caption" color="text.secondary" sx={{ display: 'block' }}>
                  {batch.configJson}
                </Typography>
              </Box>
              <Button
                size="small"
                onClick={async () => {
                  try {
                    const detail = await evaluationApi.datasetBatch(batch.id);
                    const failed =
                      detail.cases.find(item => item.status === 'FAILED' && item.runId) ||
                      detail.cases.find(item => item.runId);
                    if (failed && onOpenRun) onOpenRun(failed.runId);
                  } catch (failure) {
                    setError(failure.message);
                  }
                }}
              >
                查看失败运行
              </Button>
            </Stack>
          ))}
        </Stack>
      </Box>
    </Stack>
  );
}
