import { useState } from 'react';
import { Box, Button, Chip, Stack, Typography } from '@mui/material';
import SchoolOutlined from '@mui/icons-material/SchoolOutlined';
import PlayArrowRounded from '@mui/icons-material/PlayArrowRounded';
import { useNavigate } from 'react-router-dom';
import { useQueryClient } from '@tanstack/react-query';
import { tasksApi } from '../../api/tasks';
import { useToast } from '../../components/ToastProvider';
import { googleTokens } from '../../theme/googleTheme';
import { useI18n } from '../../i18n/I18nProvider';

function playbooks(t) {
  return [
    {
      id: 'double-analysis',
      title: t('playbooks.doubleAnalysis.title'),
      tag: t('playbooks.doubleAnalysis.tag'),
      description: t('playbooks.doubleAnalysis.description'),
      payload: {
        goal: t('playbooks.doubleAnalysis.goal'),
        scenario: 'normal',
        modelMode: 'mock',
        entryKind: 'GENERAL',
        workflowTemplate: 'dual-analysis',
        reviewPlan: false,
        constraints: [
          t('playbooks.doubleAnalysis.constraint1'),
          t('playbooks.doubleAnalysis.constraint2')
        ],
        acceptanceCriteria: [t('playbooks.doubleAnalysis.acceptance1')]
      }
    },
    {
      id: 'breakpoint-review',
      title: t('playbooks.breakpoint.title'),
      tag: t('playbooks.breakpoint.tag'),
      description: t('playbooks.breakpoint.description'),
      payload: {
        goal: t('playbooks.breakpoint.goal'),
        scenario: 'normal',
        modelMode: 'mock',
        entryKind: 'DESIGN',
        workflowTemplate: 'dual-analysis',
        reviewPlan: true,
        constraints: [t('playbooks.breakpoint.constraint1')]
      }
    },
    {
      id: 'gateway-guard',
      title: t('playbooks.gateway.title'),
      tag: t('playbooks.gateway.tag'),
      description: t('playbooks.gateway.description'),
      payload: {
        goal: t('playbooks.gateway.goal'),
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
}

/** 首页免 API Key 教学演示剧本：让开源学习者 1 分钟内一键体验玻璃盒多 Agent 协作。 */
export default function TeachingPlaybooks() {
  const navigate = useNavigate();
  const toast = useToast();
  const queryClient = useQueryClient();
  const { t, locale } = useI18n();
  const [busyId, setBusyId] = useState(null);
  const items = playbooks(t);

  const launch = async playbook => {
    setBusyId(playbook.id);
    try {
      const created = await tasksApi.create({ ...playbook.payload, locale });
      queryClient.invalidateQueries({ queryKey: ['tasks'] });
      toast.success(t('playbooks.started', { title: playbook.title }));
      navigate(`/runs/${created.runId}`);
    } catch (error) {
      toast.error(error.message || t('playbooks.failed'));
    } finally {
      setBusyId(null);
    }
  };

  return (
    <Box sx={{ mt: 4 }}>
      <Stack direction="row" spacing={1} sx={{ alignItems: 'center', mb: 1.25 }}>
        <SchoolOutlined sx={{ fontSize: 19, color: googleTokens.primary }} />
        <Typography sx={{ fontSize: 15, fontWeight: 500 }}>{t('playbooks.title')}</Typography>
      </Stack>
      <Box
        sx={{
          display: 'grid',
          gridTemplateColumns: { xs: '1fr', md: 'repeat(3, 1fr)' },
          gap: 1.5
        }}
      >
        {items.map(item => (
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
              {busyId === item.id ? t('playbooks.starting') : t('playbooks.run')}
            </Button>
          </Stack>
        ))}
      </Box>
    </Box>
  );
}
