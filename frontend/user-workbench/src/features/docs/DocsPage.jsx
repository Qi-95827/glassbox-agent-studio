import { Box, Button, Chip, Stack, Typography } from '@mui/material';
import AccountTreeOutlined from '@mui/icons-material/AccountTreeOutlined';
import PlayCircleOutlineRounded from '@mui/icons-material/PlayCircleOutlineRounded';
import MoveToInboxOutlined from '@mui/icons-material/MoveToInboxOutlined';
import SmartToyOutlined from '@mui/icons-material/SmartToyOutlined';
import FactCheckOutlined from '@mui/icons-material/FactCheckOutlined';
import { useNavigate } from 'react-router-dom';
import { usePageChrome } from '../../layout/pageChrome';
import { googleTokens } from '../../theme/googleTheme';

const guides = [
  {
    icon: <PlayCircleOutlineRounded />,
    title: '从目标到计划',
    points: [
      '在「工作台」描述目标、约束与验收条件，选择入口类型。',
      '预算、工作流、授权文件与模型参数收在「高级运行配置」，默认折叠。',
      '默认「先审阅计划再运行」；确认计划后才开始执行。'
    ]
  },
  {
    icon: <AccountTreeOutlined />,
    title: '观察执行',
    points: [
      '「观察流程」展示服务端计划节点、依赖、状态与耗时。',
      '点击节点在右侧查看工具回执、上游交接、检查证据与时间线。',
      '「逐步回放」按事件序号回放判断、模型与工具记录。'
    ]
  },
  {
    icon: <MoveToInboxOutlined />,
    title: '验收交付',
    points: [
      '「交付说明」汇总运行结论、产出文件、核对情况与未验证事项。',
      '文件预览会核对当前 SHA-256，版本变化时拒绝返回旧正文。',
      'Git 差异、人工审查、本地提交与推送 / 合并各需显式批准。'
    ]
  },
  {
    icon: <SmartToyOutlined />,
    title: '私有 Agent',
    points: [
      'Skill、只读工具与 Agent 只对当前账号可见。',
      '发布后版本固定，旧运行继续使用发布时的组件。',
      '私有 Agent 只能进入分析或审查节点，不能自行扩大授权。'
    ]
  },
  {
    icon: <FactCheckOutlined />,
    title: '评测与监控',
    points: [
      '种子评测使用模拟模型与工具，不访问真实外部服务。',
      '数据集草稿可编辑，发布后版本不可变。',
      '监控只统计本机数据库保留记录，已知用量不是完整总量。'
    ]
  }
];

/** 使用文档：将关键边界与操作路径放在同一页。 */
export default function DocsPage() {
  const navigate = useNavigate();
  usePageChrome({ trail: [{ label: '工作空间' }, { label: '文档' }] });

  return (
    <Box sx={{ overflowY: 'auto', height: '100%' }}>
      <Box sx={{ maxWidth: 900, mx: 'auto', px: { xs: 2, sm: 3 }, py: { xs: 3, md: 4 } }}>
        <Typography component="h1" sx={{ fontSize: 22, fontWeight: 500 }}>
          使用文档
        </Typography>
        <Typography variant="body2" color="text.secondary" sx={{ mb: 3 }}>
          Agent Studio 把计划、执行、交接、证据与交付放进同一条可追溯链路。
        </Typography>

        <Stack spacing={2}>
          {guides.map(guide => (
            <Box
              key={guide.title}
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
          <Typography sx={{ fontSize: 14, fontWeight: 600, mb: 0.75 }}>安全边界</Typography>
          <Stack direction="row" spacing={0.75} useFlexGap sx={{ mb: 1, flexWrap: 'wrap' }}>
            <Chip size="small" label="同源会话" />
            <Chip size="small" label="CSRF 双提交校验" />
            <Chip size="small" label="文件级授权" />
            <Chip size="small" label="证据版本核对" />
            <Chip size="small" label="模拟 / 真实分开" />
          </Stack>
          <Typography variant="body2" color="text.secondary">
            页面只是服务端状态的投影；任何按钮动作都通过受控 API
            并重新校验版本、归属与权限。模型输出不能作为验收证据。
          </Typography>
        </Box>

        <Stack direction="row" spacing={1} sx={{ mt: 3 }}>
          <Button variant="contained" onClick={() => navigate('/?new=1')}>
            新建任务
          </Button>
          <Button variant="outlined" onClick={() => navigate('/runs')}>
            查看运行历史
          </Button>
        </Stack>
      </Box>
    </Box>
  );
}
