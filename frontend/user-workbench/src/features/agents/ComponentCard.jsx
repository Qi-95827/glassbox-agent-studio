import { Box, Button, Chip, Stack, Typography } from '@mui/material';
import StatusChip from '../../components/StatusChip';
import { googleTokens } from '../../theme/googleTheme';

/** 私有组件卡片：状态、版本与发布动作集中展示。 */
export default function ComponentCard({ row, children, onEdit, onPublish, onArchive, onVersions }) {
  return (
    <Box
      sx={{
        bgcolor: '#FFFFFF',
        border: `1px solid ${googleTokens.outline}`,
        borderRadius: 4,
        p: { xs: 2, sm: 2.25 }
      }}
    >
      <Stack
        direction={{ xs: 'column', sm: 'row' }}
        spacing={1.2}
        sx={{ alignItems: { xs: 'flex-start', sm: 'center' }, justifyContent: 'space-between' }}
      >
        <Stack
          direction="row"
          spacing={0.8}

          sx={{ minWidth: 0, alignItems: 'center', flexWrap: 'wrap' }}
        >
          <Typography sx={{ fontSize: 15, fontWeight: 500, overflowWrap: 'anywhere' }}>
            {row.name}
          </Typography>
          <StatusChip value={row.status} />
          <Chip
            size="small"
            label={`v${row.currentVersion}`}
            sx={{ bgcolor: '#E8F0FE', color: '#1967D2' }}
          />

          {row.draftChanged && (
            <Chip
              size="small"
              label="草稿有未发布修改"
              sx={{ bgcolor: '#FEF7E0', color: '#B06000' }}
            />
          )}
        </Stack>
        <Stack direction="row" spacing={0.5} sx={{ flexShrink: 0 }}>
          <Button size="small" onClick={onEdit}>
            编辑
          </Button>
          <Button size="small" variant="outlined" onClick={onPublish}>
            发布
          </Button>
          <Button size="small" color="inherit" onClick={onVersions}>
            版本
          </Button>
          <Button size="small" color="inherit" onClick={onArchive}>
            归档
          </Button>
        </Stack>
      </Stack>
      <Box sx={{ mt: 1 }}>{children}</Box>
    </Box>
  );
}
