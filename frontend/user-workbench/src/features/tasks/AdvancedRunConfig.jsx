import {
  Accordion,
  AccordionDetails,
  AccordionSummary,
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
import ExpandMoreRounded from '@mui/icons-material/ExpandMoreRounded';
import TuneRounded from '@mui/icons-material/TuneRounded';
import { splitLines } from '../../utils/format';
import { googleTokens } from '../../theme/googleTheme';
import { workflowCompatible } from '../bug/bugEntryDefaults';
import AuthorizedFilePicker from '../workspaces/AuthorizedFilePicker';
import RoleBudgetEditor from '../agents/RoleBudgetEditor';
import ToolBudgetEditor from '../tools/ToolBudgetEditor';
import NodeBudgetEditor from '../workflows/NodeBudgetEditor';
import EntryRoutePreview from '../workflows/EntryRoutePreview';

const workflows = [
  ['compatibility', '兼容流程（自动路由）', true],
  ['dual-analysis', '双视角分析（纯文本）', 'text'],
  ['workspace-analysis', '授权文件分析（只读）', 'analysis'],
  ['workspace-change', '分析、修改与审查', 'change'],
  ['bug-fix', '模拟 Bug 复现、修复与回归', 'bug']
];

const limits = [
  ['rounds', '最大轮次', '不设上限'],
  ['tools', '工具总次数', '不设上限'],
  ['perTool', '单工具次数'],
  ['seconds', '时间（秒）'],
  ['maxAgentRounds', '单 Agent 模型调用'],
  ['maxAgentTools', '单 Agent 工具调用'],
  ['tokenLimit', '已报告 Token 上限'],
  ['identicalLimit', '连续相同工具上限']
];

/**
 * 高级运行配置：低频的限额、工作流、授权范围与预算全部折叠。
 * 只编辑任务草稿；兼容性判断只是前端预检，最终由服务端校验。
 */
export default function AdvancedRunConfig({ draft, patch, policy, onOpenBug, onOpenPlan }) {
  const fileScopes = splitLines(draft.fileScopes);
  const directories = splitLines(draft.directories);
  const acceptance = splitLines(draft.acceptance);
  const hasPlannableRoute =
    Boolean(draft.workflowTemplate) ||
    ['CODE', 'TEST', 'REQUIREMENT', 'DESIGN', 'BUG'].includes(draft.entryKind);
  const state = {
    hasWorkspace: Boolean(draft.workspaceId),
    hasBug: Boolean(draft.bugId) || draft.entryKind === 'BUG',
    writeEnabled: Boolean(draft.writeEnabled),
    memoryEnabled: Boolean(draft.memoryEnabled)
  };
  const eligible = {
    text: workflowCompatible('dual-analysis', state),
    analysis: workflowCompatible('workspace-analysis', state),
    change: workflowCompatible('workspace-change', state),
    bug: workflowCompatible('bug-fix', state)
  };
  const mismatch =
    Boolean(draft.workflowTemplate) && !workflowCompatible(draft.workflowTemplate, state);

  return (
    <Accordion sx={{ mt: 1.5, bgcolor: 'transparent', border: 'none' }}>
      <AccordionSummary
        expandIcon={<ExpandMoreRounded fontSize="small" />}
        sx={{ px: 1, minHeight: 42, '& .MuiAccordionSummary-content': { my: 0.75 } }}
      >
        <Stack direction="row" spacing={0.75} sx={{ alignItems: 'center' }}>
          <TuneRounded sx={{ fontSize: 17, color: googleTokens.textSecondary }} />
          <Typography variant="body2" sx={{ fontWeight: 500 }}>
            高级运行配置
          </Typography>
          <Typography variant="caption" color="text.secondary">
            限额、工作流、授权范围与预算默认折叠
          </Typography>
        </Stack>
      </AccordionSummary>
      <AccordionDetails sx={{ px: 0 }}>
        <Stack spacing={1.75}>
          <Box
            sx={{
              display: 'grid',
              gridTemplateColumns: {
                xs: '1fr',
                sm: 'repeat(2, minmax(0,1fr))',
                lg: 'repeat(3, minmax(0,1fr))'
              },
              gap: 1.5
            }}
          >
            <TextField
              select
              size="small"
              label="工作流"
              value={draft.workflowTemplate ?? 'compatibility'}
              onChange={event =>
                patch({
                  workflowTemplate:
                    event.target.value === 'compatibility' ? null : event.target.value
                })
              }
            >
              {workflows.map(([value, label, gate]) => (
                <MenuItem key={value} value={value} disabled={gate !== true && !eligible[gate]}>
                  {label}
                </MenuItem>
              ))}
            </TextField>
            <TextField
              select
              size="small"
              label="模拟工具场景"
              value={draft.scenario}
              onChange={event => patch({ scenario: event.target.value })}
            >
              <MenuItem value="normal">正常执行</MenuItem>
              <MenuItem value="fail">工具失败</MenuItem>
              <MenuItem value="slow">慢速执行（测试取消）</MenuItem>
              <MenuItem value="repeat" disabled={draft.model === 'deepseek'}>
                重复调用
              </MenuItem>
            </TextField>
            <TextField
              size="small"
              type="number"
              label="费用硬预算（微单位）"
              value={draft.maxCost}
              onChange={event => patch({ maxCost: event.target.value })}
              placeholder="留空不限额"
            />
          </Box>

          <Box
            sx={{
              display: 'grid',
              gridTemplateColumns: { xs: '1fr', md: 'repeat(2, minmax(0,1fr))' },
              gap: 1.5
            }}
          >
            <TextField
              size="small"
              label="任务约束（每行一条，可选）"
              multiline
              minRows={2}
              value={draft.constraints}
              onChange={event => patch({ constraints: event.target.value })}
              placeholder="例如：只修改授权文件"
            />
            <TextField
              size="small"
              label="验收条件（每行一条，可选）"
              multiline
              minRows={2}
              value={draft.acceptance}
              onChange={event => patch({ acceptance: event.target.value })}
              placeholder="例如：指定回归测试通过"
              helperText="验收条件需要证据，模型回答不等于验证通过。"
            />
          </Box>

          {draft.workspaceId && (
            <Box
              sx={{
                border: `1px solid ${googleTokens.outline}`,
                borderRadius: 3,
                p: 1.75,
                bgcolor: '#FBFCFD'
              }}
            >
              <Typography sx={{ fontSize: 13, fontWeight: 600, mb: 0.75 }}>
                工作区授权范围
              </Typography>
              <Stack spacing={1.5}>
                <TextField
                  size="small"
                  label="授权文件（每行一个相对路径）"
                  multiline
                  minRows={2}
                  value={draft.fileScopes}
                  onChange={event => patch({ fileScopes: event.target.value })}
                  placeholder="例如 src/App.jsx"
                  helperText="最多 20 个文件；任务只能访问所列的现有文件。"
                />
                <AuthorizedFilePicker
                  workspaceId={draft.workspaceId}
                  value={draft.fileScopes}
                  onChange={next => patch({ fileScopes: next })}
                />
                <Box
                  sx={{
                    display: 'grid',
                    gridTemplateColumns: { xs: '1fr', md: 'repeat(2, minmax(0,1fr))' },
                    gap: 1.5
                  }}
                >
                  <TextField
                    size="small"
                    label="目录范围（每行一个，可选）"
                    multiline
                    minRows={2}
                    value={draft.directories}
                    onChange={event => patch({ directories: event.target.value })}
                  />
                  <TextField
                    size="small"
                    label="排除路径（每行一个，可选）"
                    multiline
                    minRows={2}
                    value={draft.exclusions}
                    onChange={event => patch({ exclusions: event.target.value })}
                  />
                </Box>
                <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1}>
                  <FormControlLabel
                    control={
                      <Checkbox
                        size="small"
                        checked={draft.writeEnabled}
                        onChange={event => patch({ writeEnabled: event.target.checked })}
                      />
                    }
                    label={<Typography variant="body2">允许 Agent 修改所列文件</Typography>}
                  />
                  <FormControlLabel
                    control={
                      <Checkbox
                        size="small"
                        checked={draft.memoryEnabled}
                        onChange={event => patch({ memoryEnabled: event.target.checked })}
                      />
                    }
                    label={<Typography variant="body2">读取项目背景记忆</Typography>}
                  />
                </Stack>
                {draft.writeEnabled && (
                  <Alert severity="warning">
                    写入授权仅覆盖上方所列文件；修改结果仍需独立审查与人工批准才能提交。
                  </Alert>
                )}
              </Stack>
            </Box>
          )}

          {draft.entryKind === 'BUG' && (
            <Stack direction="row" spacing={1} sx={{ alignItems: 'center', flexWrap: 'wrap' }}>
              <TextField
                size="small"
                label="模拟 Bug"
                value={draft.bugId}
                onChange={event => patch({ bugId: event.target.value })}
                sx={{ minWidth: 220 }}
              />
              <Button variant="outlined" onClick={onOpenBug}>
                打开 Bug 验证台
              </Button>
            </Stack>
          )}

          <Box
            sx={{
              display: 'grid',
              gridTemplateColumns: {
                xs: '1fr',
                sm: 'repeat(2, minmax(0,1fr))',
                lg: 'repeat(4, minmax(0,1fr))'
              },
              gap: 1.5
            }}
          >
            {limits.map(([key, label, placeholder]) => (
              <TextField
                key={key}
                size="small"
                type="number"
                label={label}
                value={draft[key]}
                onChange={event => patch({ [key]: event.target.value })}
                placeholder={placeholder}
              />
            ))}
          </Box>

          <RoleBudgetEditor
            value={draft.roleBudgets}
            onChange={value => patch({ roleBudgets: value })}
          />
          <ToolBudgetEditor
            value={draft.toolBudgets}
            onChange={value => patch({ toolBudgets: value })}
          />
          <NodeBudgetEditor
            template={draft.workflowTemplate}
            value={draft.nodeBudgets}
            onChange={value => patch({ nodeBudgets: value })}
          />

          {hasPlannableRoute && (
            <FormControlLabel
              control={
                <Checkbox
                  size="small"
                  checked={draft.reviewPlan}
                  onChange={event => patch({ reviewPlan: event.target.checked })}
                />
              }
              label={<Typography variant="body2">先审阅计划再运行</Typography>}
            />
          )}

          {policy && (
            <Box
              sx={{
                p: 1.75,
                borderRadius: 3,
                bgcolor: '#FBFCFD',
                border: '1px solid',
                borderColor: 'divider'
              }}
            >
              <Typography variant="body2" sx={{ fontWeight: 600, mb: 0.4 }}>
                入口约定 · {policy.label}
              </Typography>
              <Typography variant="caption" color="text.secondary" sx={{ display: 'block' }}>
                必填：{policy.requiredInputs.join('；')}
              </Typography>
              <Typography variant="caption" color="text.secondary" sx={{ display: 'block' }}>
                交付物：{policy.deliverables.join('；')}
              </Typography>
              <Typography variant="caption" color="text.secondary" sx={{ display: 'block' }}>
                默认验收：{policy.defaultAcceptance.join('；')}
              </Typography>
            </Box>
          )}

          {mismatch && (
            <Alert severity="warning">
              当前任务设置与所选工作流不兼容，请调整工作区或写入授权。
            </Alert>
          )}

          <EntryRoutePreview
            entryKind={draft.entryKind}
            workspaceId={draft.workspaceId}
            writeEnabled={draft.writeEnabled}
            fileScopes={fileScopes}
            directoryScopes={directories}
            workflowTemplate={draft.workflowTemplate}
          />

          {draft.entryKind === 'CODE' &&
            draft.workspaceId &&
            fileScopes.length > 0 &&
            acceptance.length > 0 && (
              <Button variant="outlined" onClick={onOpenPlan} sx={{ alignSelf: 'flex-start' }}>
                生成执行计划草稿
              </Button>
            )}
        </Stack>
      </AccordionDetails>
    </Accordion>
  );
}
