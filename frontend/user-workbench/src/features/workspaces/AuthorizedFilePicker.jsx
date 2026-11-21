import { useRef, useState } from 'react';
import {
  Accordion,
  AccordionDetails,
  AccordionSummary,
  Alert,
  Box,
  Button,
  Chip,
  Stack,
  TextField,
  Typography
} from '@mui/material';
import ExpandMoreRounded from '@mui/icons-material/ExpandMoreRounded';
import { workspacesApi } from '../../api/workspaces';
import { splitLines } from '../../utils/format';

/** 仅提供文件候选；选中路径仍需参与任务创建，才形成服务端授权。 */
export default function AuthorizedFilePicker({ workspaceId, value, onChange }) {
  const [keyword, setKeyword] = useState('');
  const [result, setResult] = useState(null);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);
  const request = useRef(0);
  const selected = splitLines(value);

  async function search() {
    const id = ++request.current;
    setError('');
    setLoading(true);
    try {
      const next = await workspacesApi.files(workspaceId, keyword);
      if (request.current === id) setResult(next);
    } catch (failure) {
      if (request.current === id) {
        setError(failure.message);
        setResult(null);
      }
    } finally {
      if (request.current === id) setLoading(false);
    }
  }

  function add(path) {
    if (selected.includes(path) || selected.length >= 20) return;
    onChange([...selected, path].join('\n'));
  }

  return (
    <Accordion
      disableGutters
      sx={{ border: '1px solid', borderColor: 'divider', boxShadow: 'none' }}
    >
      <AccordionSummary expandIcon={<ExpandMoreRounded fontSize="small" />}>
        <Typography variant="body2" sx={{ fontWeight: 500 }}>
          从 Git 已跟踪文件中选择
        </Typography>
        {selected.length > 0 && (
          <Chip size="small" label={`已选 ${selected.length}`} sx={{ ml: 1, bgcolor: '#E8F0FE' }} />
        )}
      </AccordionSummary>
      <AccordionDetails>
        <Typography variant="caption" color="text.secondary">
          按路径查找候选并逐个添加。只有加入授权列表的文件才会供本次任务使用，最多 20 个。
        </Typography>
        <Stack direction="row" spacing={1} sx={{ my: 1 }}>
          <TextField
            size="small"
            fullWidth
            value={keyword}
            onChange={event => {
              request.current += 1;
              setKeyword(event.target.value);
              setResult(null);
              setError('');
            }}
            onKeyDown={event => {
              if (event.key === 'Enter') {
                event.preventDefault();
                search();
              }
            }}
            placeholder="输入文件名或目录，留空显示全部候选"
            slotProps={{ htmlInput: { maxLength: 100 } }}
          />

          <Button variant="outlined" onClick={search} loading={loading} sx={{ flexShrink: 0 }}>
            查找
          </Button>
        </Stack>
        {error && (
          <Alert severity="error" sx={{ mb: 1 }}>
            文件候选查询失败，可继续手动输入路径。{error}
          </Alert>
        )}
        {result && (
          <Box>
            {result.truncated && (
              <Typography variant="caption" color="text.secondary">
                候选超过 100 个，请输入更具体的关键词。
              </Typography>
            )}
            {result.paths?.length === 0 && (
              <Typography variant="body2" color="text.secondary">
                没有找到符合条件的已跟踪文件。
              </Typography>
            )}
            <Stack
              spacing={0.25}
              sx={{ maxHeight: 210, overflowY: 'auto', mt: result.paths?.length ? 0.5 : 0 }}
            >
              {(result.paths ?? []).map(path => {
                const added = selected.includes(path);
                return (
                  <Stack
                    key={path}
                    direction="row"

                    spacing={1}
                    sx={{ py: 0.4, borderBottom: '1px solid #F1F3F4', alignItems: 'center' }}
                  >
                    <Typography
                      variant="caption"
                      sx={{
                        flex: 1,
                        minWidth: 0,
                        overflowWrap: 'anywhere',
                        fontFamily: 'monospace'
                      }}
                    >
                      {path}
                    </Typography>
                    <Button
                      size="small"
                      disabled={added || selected.length >= 20}
                      onClick={() => add(path)}
                    >
                      {added ? '已添加' : '添加'}
                    </Button>
                  </Stack>
                );
              })}
            </Stack>
          </Box>
        )}
      </AccordionDetails>
    </Accordion>
  );
}
