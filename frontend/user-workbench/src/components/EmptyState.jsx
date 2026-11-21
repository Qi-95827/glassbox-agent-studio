import { Box, Button, Stack, Typography } from '@mui/material';
import InboxOutlined from '@mui/icons-material/InboxOutlined';

/** 统一空状态：浅灰图标 + 说明 + 明确的下一步，不使用生硬留白。 */
export default function EmptyState({ icon, title, description, action, compact = false }) {
  return (
    <Stack
      spacing={1.2}
      sx={{
        py: compact ? 2.75 : 6,
        px: 3,
        color: 'text.secondary',
        textAlign: 'center',
        alignItems: 'center',
        justifyContent: 'center'
      }}
    >
      <Box
        sx={{
          width: 48,
          height: 48,
          borderRadius: '50%',
          bgcolor: '#F1F3F4',
          color: '#9AA0A6',
          display: 'grid',
          placeItems: 'center'
        }}
      >
        {icon || <InboxOutlined />}
      </Box>
      <Typography sx={{ fontSize: 15, fontWeight: 500, color: 'text.primary' }}>
        {title || '暂无数据'}
      </Typography>
      {description && (
        <Typography variant="body2" sx={{ maxWidth: 420 }}>
          {description}
        </Typography>
      )}
      {action && (
        <Button size="small" variant="outlined" onClick={action.onClick} sx={{ mt: 0.5 }}>
          {action.label}
        </Button>
      )}
    </Stack>
  );
}
