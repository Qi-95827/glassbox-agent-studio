import { useState } from 'react';
import { Box, Button, Chip, Stack, Typography } from '@mui/material';
import SchoolOutlined from '@mui/icons-material/SchoolOutlined';
import PlayArrowRounded from '@mui/icons-material/PlayArrowRounded';
import { useNavigate } from 'react-router-dom';
import { useQueryClient } from '@tanstack/react-query';
import { tasksApi } from '../../api/tasks';
import { useToast } from '../../components/ToastProvider';
import { googleTokens } from '../../theme/googleTheme';

const PLAYBOOKS = [
  {
    id: 'double-analysis',
    title: '多视角分工与交接信封',
    tag: '推荐入门',
    description:
      '双分析节点独立拆解需求与风险，再通过结构化交接信封汇合至汇总节点，完整透视装配、Prompt 与交接。',
    payload: {
      goal: '【教学示例】设计一个可视化冒泡排序教学网页，给出需求拆解、关键交互约束与验收风险。',
      scenario: 'normal',
      modelMode: 'mock',
      entryKind: 'GENERAL',
      workflowTemplate: 'double_analysis',
      reviewPlan: false,
      constraints: ['纯前端静态实现，无需后端接口', '必须支持单步前进与高亮比较元素'],
      acceptanceCriteria: ['包含清晰的需求拆分与风险清单']
    }
  },
  {
    id: 'breakpoint-review',
    title: '先审计划 + 断点单步观察',
    tag: '可调控断点',
    description:
      '先停在计划确认阶段，你可以在画布任意节点打上红色断点，逐跳检查上游交接后再手动放行。',
    payload: {
      goal: '【教学示例】评审登录模块重构方案，确认节点计划并在汇总节点前打断点人工检查交接。',
      scenario: 'normal',
      modelMode: 'mock',
      entryKind: 'DESIGN',
      workflowTemplate: 'double_analysis',
      reviewPlan: true,
      constraints: ['不得破坏现有 Cookie 会话契约']
    }
  },
  {
    id: 'gateway-guard',
    title: 'ToolGateway 限额与安检演示',
    tag: '安全边界',
    description:
      '设置严格工具调用上限，在右侧 Tool Gateway 面板观察 6 道安检门如何记录入参、权限与预算边界。',
    payload: {
      goal: '【教学示例】验证受控工具网关的预算与重复调用保护策略。',
      scenario: 'normal',
      modelMode: 'mock',
      entryKind: 'GENERAL',
      maxRounds: 4,
      maxTools: 2,
      maxIdenticalTools: 1,
      reviewPlan: false
    }
  }
];

/** 首页免 API Key 教学演示剧本：让开源学习者 1 分钟内一键体验玻璃盒多 Agent 协作。 */
export default function TeachingPlaybooks() {
  const navigate = useNavigate();
  const toast = useToast();
  const queryClient = useQueryClient();
  const [busyId, setBusyId] = useState(null);

  const launch = async playbook => {
    setBusyId(playbook.id);
    try {
      const created = await tasksApi.create(playbook.payload);
      queryClient.invalidateQueries({ queryKey: ['tasks'] });
      toast.success(`已启动教学剧本「${playbook.title}」`);
      navigate(`/runs/${created.runId}`);
    } catch (error) {
      toast.error(error.message || '无法启动教学剧本');
    } finally {
      setBusyId(null);
    }
  };

  return (
    <Box sx={{ mt: 4 }}>
      <Stack direction="row" spacing={1} sx={{ alignItems: 'center', mb: 1.25 }}>
        <SchoolOutlined sx={{ fontSize: 19, color: googleTokens.primary }} />
        <Typography sx={{ fontSize: 15, fontWeight: 500 }}>
          免 Key 教学演示剧本（玻璃盒快速体验）
        </Typography>
      </Stack>
      <Box
        sx={{
          display: 'grid',
          gridTemplateColumns: { xs: '1fr', md: 'repeat(3, 1fr)' },
          gap: 1.5
        }}
      >
        {PLAYBOOKS.map(item => (
          <Stack
            key={item.id}
            spacing={1}
            sx={{
              p: 2,
              borderRadius: 4,
              bgcolor: '#FFFFFF',
              border: `1px solid ${googleTokens.outline}`,
              justifyContent: 'space-between'
            }}
          >
            <Box>
              <Stack
                direction="row"
                spacing={0.75}
                sx={{ alignItems: 'center', justifyContent: 'space-between', mb: 0.75 }}
              >
                <Typography sx={{ fontSize: 14, fontWeight: 600 }}>{item.title}</Typography>
                <Chip
                  size="small"
                  label={item.tag}
                  sx={{ bgcolor: googleTokens.infoBg, color: googleTokens.infoFg, fontSize: 11 }}
                />
              </Stack>
              <Typography variant="caption" color="text.secondary" sx={{ lineHeight: 1.55 }}>
                {item.description}
              </Typography>
            </Box>
            <Button
              size="small"
              variant="outlined"
              startIcon={<PlayArrowRounded />}
              disabled={busyId !== null}
              onClick={() => launch(item)}
              sx={{ alignSelf: 'flex-start' }}
            >
              {busyId === item.id ? '启动中…' : '一键运行并透视'}
            </Button>
          </Stack>
        ))}
      </Box>
    </Box>
  );
}
