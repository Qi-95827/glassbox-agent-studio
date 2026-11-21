import { useState } from 'react';
import { Alert, Button, Stack, TextField, Typography } from '@mui/material';
import SaveOutlined from '@mui/icons-material/SaveOutlined';
import SearchRounded from '@mui/icons-material/SearchRounded';
import { workspacesApi } from '../../api/workspaces';
import { useToast } from '../../components/ToastProvider';
import { googleTokens } from '../../theme/googleTheme';

/** 读取或编辑授权工作区内的现有文件；保存时核对读取时的摘要。 */
export default function WorkspaceFileEditor({ workspaceId }) {
  const toast = useToast();
  const [path, setPath] = useState('');
  const [file, setFile] = useState(null);
  const [draft, setDraft] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  const read = async () => {
    if (!path.trim()) return;
    setBusy(true);
    setError('');
    try {
      const loaded = await workspacesApi.readFile(workspaceId, path.trim());
      setFile(loaded);
      setDraft(loaded.content ?? '');
    } catch (failure) {
      setError(failure.message);
      setFile(null);
    } finally {
      setBusy(false);
    }
  };

  const save = async () => {
    setBusy(true);
    setError('');
    try {
      const saved = await workspacesApi.writeFile(workspaceId, {
        path: file.path,
        expectedHash: file.sha256,
        content: draft
      });
      setFile(saved);
      setDraft(saved.content ?? draft);
      toast.success('已保存到独立工作区');
    } catch (failure) {
      setError(failure.message);
    } finally {
      setBusy(false);
    }
  };

  return (
    <Stack spacing={1.5}>
      <Typography variant="body2" color="text.secondary">
        只读取/修改当前工作区内的现有文件；保存时服务端会核对读取时的 SHA-256，防止覆盖外部变化。
      </Typography>
      <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1}>
        <TextField
          size="small"
          label="工作区内相对路径"
          value={path}
          onChange={event => setPath(event.target.value)}
          placeholder="例如 src/App.jsx"
          sx={{ flex: 1 }}
        />

        <Button
          variant="outlined"
          startIcon={<SearchRounded />}
          loading={busy}
          onClick={read}
          disabled={!path.trim()}
        >
          读取文件
        </Button>
      </Stack>
      {error && <Alert severity="error">{error}</Alert>}
      {file && (
        <>
          <Stack direction="row" spacing={0.75} sx={{ flexWrap: 'wrap', alignItems: 'center' }}>
            <Typography
              variant="caption"
              sx={{ fontFamily: googleTokens.mono, overflowWrap: 'anywhere' }}
            >
              {file.path}
            </Typography>
            <Typography variant="caption" color="text.secondary">
              SHA-256 {file.sha256?.slice(0, 16)}…
            </Typography>
          </Stack>
          <TextField
            multiline
            minRows={14}
            maxRows={26}
            value={draft}
            onChange={event => setDraft(event.target.value)}
            slotProps={{
              input: {
                sx: { fontFamily: googleTokens.mono, fontSize: 12.5, lineHeight: 1.6 }
              }
            }}
          />

          <Button
            variant="contained"
            startIcon={<SaveOutlined />}
            loading={busy}
            onClick={save}
            disabled={draft === file.content}
            sx={{ alignSelf: 'flex-start' }}
          >
            保存到独立工作区
          </Button>
        </>
      )}
    </Stack>
  );
}
