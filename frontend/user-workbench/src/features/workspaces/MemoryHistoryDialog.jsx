import { useEffect, useState } from 'react';
import { Alert, Button, CircularProgress, Stack, Typography } from '@mui/material';
import WorkbenchDialog from '../../components/WorkbenchDialog';
import { workspacesApi } from '../../api/workspaces';
import { dateTime } from '../../utils/format';

const categories = {
  PROJECT_CONVENTION: '项目约定',
  PROJECT_BACKGROUND: '项目背景',
  PRECAUTION: '注意事项'
};

/** 显式按需读取项目背景历史；不会恢复旧版本或撤回已发送内容。 */
export default function MemoryHistoryDialog({ open, workspaceId, memoryId, onClose }) {
  const [items, setItems] = useState([]);
  const [next, setNext] = useState(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');

  const load = async before => {
    setLoading(true);
    setError('');
    try {
      const page = await workspacesApi.memoryHistory(workspaceId, memoryId, before);
      setItems(previous => (before == null ? page.items : [...previous, ...page.items]));
      setNext(page.nextBeforeVersion);
    } catch (failure) {
      setError(failure.message);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    if (!open) return;
    setItems([]);
    setNext(null);
    load(null);
    // 仅在打开或目标变化时重新读取。
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, workspaceId, memoryId]);

  return (
    <WorkbenchDialog
      open={open}
      onCancel={onClose}
      footer={null}
      width={700}
      title="项目背景历史"
      subtitle="历史版本含原文；查看不会恢复旧版本。"
    >
      <Stack spacing={1.2} sx={{ pt: 0.5 }}>
        <Alert severity="info">
          查看历史不会撤回已经发送给模型的内容；删除后新任务不再读取，但历史版本仍保留。
        </Alert>
        {error && <Alert severity="error">{error}</Alert>}
        {items.map(item => (
          <Stack
            key={item.version}
            spacing={0.3}
            sx={{ borderBottom: '1px solid', borderColor: 'divider', pb: 1 }}
          >
            <Typography variant="body2" sx={{ fontWeight: 600 }}>
              v{item.version} · {categories[item.category] || item.category} ·{' '}
              {item.status === 'DELETED'
                ? '已删除'
                : item.status === 'ACTIVE'
                  ? '有效'
                  : '状态未知'}
            </Typography>
            <Typography variant="caption" color="text.secondary">
              {dateTime(item.changedAt)}
            </Typography>
            <Typography variant="body2" sx={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}>
              {item.content}
            </Typography>
          </Stack>
        ))}
        {loading && <CircularProgress size={18} />}
        {!loading && !error && items.length === 0 && (
          <Typography variant="body2" color="text.secondary">
            没有保留历史记录。
          </Typography>
        )}
        {next != null && (
          <Button variant="outlined" onClick={() => load(next)} sx={{ alignSelf: 'flex-start' }}>
            加载更早版本
          </Button>
        )}
      </Stack>
    </WorkbenchDialog>
  );
}
