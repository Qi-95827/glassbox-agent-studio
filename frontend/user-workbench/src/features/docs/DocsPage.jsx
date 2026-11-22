import { Box, Button, Chip, Stack, Typography } from '@mui/material';
import AccountTreeOutlined from '@mui/icons-material/AccountTreeOutlined';
import PlayCircleOutlineRounded from '@mui/icons-material/PlayCircleOutlineRounded';
import MoveToInboxOutlined from '@mui/icons-material/MoveToInboxOutlined';
import SmartToyOutlined from '@mui/icons-material/SmartToyOutlined';
import FactCheckOutlined from '@mui/icons-material/FactCheckOutlined';
import { useNavigate } from 'react-router-dom';
import { usePageChrome } from '../../layout/pageChrome';
import { googleTokens } from '../../theme/googleTheme';
import { useI18n } from '../../i18n/I18nProvider';

function guides(t) {
  return [
    {
      id: 'plan',
      icon: <PlayCircleOutlineRounded />,
      title: t('docs.guide.plan.title'),
      points: [
        t('docs.guide.plan.point1'),
        t('docs.guide.plan.point2'),
        t('docs.guide.plan.point3')
      ]
    },
    {
      id: 'runs',
      icon: <AccountTreeOutlined />,
      title: t('docs.guide.runs.title'),
      points: [
        t('docs.guide.runs.point1'),
        t('docs.guide.runs.point2'),
        t('docs.guide.runs.point3')
      ]
    },
    {
      id: 'delivery',
      icon: <MoveToInboxOutlined />,
      title: t('docs.guide.delivery.title'),
      points: [
        t('docs.guide.delivery.point1'),
        t('docs.guide.delivery.point2'),
        t('docs.guide.delivery.point3')
      ]
    },
    {
      id: 'agents',
      icon: <SmartToyOutlined />,
      title: t('docs.guide.agents.title'),
      points: [
        t('docs.guide.agents.point1'),
        t('docs.guide.agents.point2'),
        t('docs.guide.agents.point3')
      ]
    },
    {
      id: 'evaluation',
      icon: <FactCheckOutlined />,
      title: t('docs.guide.evaluation.title'),
      points: [
        t('docs.guide.evaluation.point1'),
        t('docs.guide.evaluation.point2'),
        t('docs.guide.evaluation.point3')
      ]
    }
  ];
}

/** 使用文档：将关键边界与操作路径放在同一页。 */
export default function DocsPage() {
  const { t } = useI18n();
  const navigate = useNavigate();
  usePageChrome({ trail: [{ label: t('nav.workspace') }, { label: t('nav.docs') }] });

  return (
    <Box sx={{ overflowY: 'auto', height: '100%' }}>
      <Box sx={{ maxWidth: 900, mx: 'auto', px: { xs: 2, sm: 3 }, py: { xs: 3, md: 4 } }}>
        <Typography component="h1" sx={{ fontSize: 22, fontWeight: 500 }}>
          {t('docs.title')}
        </Typography>
        <Typography variant="body2" color="text.secondary" sx={{ mb: 3 }}>
          {t('docs.description')}
        </Typography>

        <Stack spacing={2}>
          {guides(t).map(guide => (
            <Box
              key={guide.id}
              sx={{
                bgcolor: '#FFFFFF',
                border: `1px solid ${googleTokens.outline}`,
                borderRadius: 4,
                p: { xs: 2, sm: 2.5 }
              }}
            >
              <Stack direction="row" spacing={1.1} sx={{ mb: 1, alignItems: 'center' }}>
                <Box
                  sx={{
                    width: 30,
                    height: 30,
                    borderRadius: 2,
                    bgcolor: '#E8F0FE',
                    color: '#1967D2',
                    display: 'grid',
                    placeItems: 'center',
                    '& svg': { fontSize: 18 }
                  }}
                >
                  {guide.icon}
                </Box>
                <Typography sx={{ fontSize: 15, fontWeight: 500 }}>{guide.title}</Typography>
              </Stack>
              <Stack spacing={0.6}>
                {guide.points.map(point => (
                  <Typography key={point} variant="body2" color="text.secondary">
                    · {point}
                  </Typography>
                ))}
              </Stack>
            </Box>
          ))}
        </Stack>

        <Box
          sx={{
            mt: 2,
            bgcolor: '#F8FAFF',
            border: '1px solid #D2E3FC',
            borderRadius: 4,
            p: 2.5
          }}
        >
          <Typography sx={{ fontSize: 14, fontWeight: 600, mb: 0.75 }}>
            {t('docs.boundariesTitle')}
          </Typography>
          <Stack direction="row" spacing={0.75} useFlexGap sx={{ mb: 1, flexWrap: 'wrap' }}>
            <Chip size="small" label={t('docs.boundary.session')} />
            <Chip size="small" label={t('docs.boundary.csrf')} />
            <Chip size="small" label={t('docs.boundary.files')} />
            <Chip size="small" label={t('docs.boundary.evidence')} />
            <Chip size="small" label={t('docs.boundary.simulated')} />
          </Stack>
          <Typography variant="body2" color="text.secondary">
            {t('docs.boundariesDescription')}
          </Typography>
        </Box>

        <Stack direction="row" spacing={1} sx={{ mt: 3 }}>
          <Button variant="contained" onClick={() => navigate('/?new=1')}>
            {t('nav.newTask')}
          </Button>
          <Button variant="outlined" onClick={() => navigate('/runs')}>
            {t('docs.viewHistory')}
          </Button>
        </Stack>
      </Box>
    </Box>
  );
}
