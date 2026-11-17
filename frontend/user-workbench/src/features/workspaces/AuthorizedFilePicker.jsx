import React, { useEffect, useRef, useState } from 'react';
import { Alert, Button, Input } from 'antd';

/** 仅提供文件候选；选中路径仍需由用户创建任务，才能形成服务端授权。 */
export default function AuthorizedFilePicker({ workspaceId, value, onChange, api }) {
  const [keyword, setKeyword] = useState('');
  const [result, setResult] = useState(null);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);
  const request = useRef(0);
  const active = useRef(true);
  const selected = value.split('\n').map(path => path.trim()).filter(Boolean);

  useEffect(() => {
    active.current = true;
    return () => { active.current = false; request.current += 1; };
  }, []);

  async function search() {
    const id = ++request.current;
    setError('');
    setLoading(true);
    try {
      const query = new URLSearchParams({ keyword });
      const next = await api(`/workspaces/${encodeURIComponent(workspaceId)}/files?${query}`);
      if (active.current && request.current === id) setResult(next);
    } catch (e) {
      if (active.current && request.current === id) { setError(e.message); setResult(null); }
    } finally {
      if (active.current && request.current === id) setLoading(false);
    }
  }

  function add(path) {
    if (selected.includes(path) || selected.length >= 20) return;
    onChange([...selected, path].join('\n'));
  }

  return (
    <details className="agent-card file-picker">
      <summary>从 Git 已跟踪文件中选择</summary>
      <p className="hint">按路径查找候选并逐个添加。只有加入下方授权列表的文件才会供本次任务使用。</p>
      <div className="file-picker-search">
        <Input aria-label="文件路径关键词" value={keyword} maxLength={100} onChange={e => { request.current += 1; setKeyword(e.target.value); setResult(null); setError(''); setLoading(false); }} onPressEnter={search} placeholder="输入文件名或目录，留空显示全部候选" />
        <Button onClick={search} loading={loading}>查找文件</Button>
      </div>
      {error && <Alert type="error" showIcon message="文件候选查询失败，可继续手动输入路径。" description={error} />}
      {result && <div aria-live="polite">
        {result.truncated && <p className="hint">候选超过 100 个，请输入更具体的关键词。</p>}
        {result.paths.length === 0 && <p className="hint">没有找到符合条件的已跟踪文件。</p>}
        <ul className="file-picker-list">{result.paths.map(path => {
          const added = selected.includes(path);
          return <li key={path}><span title={path}>{path}</span><Button size="small" disabled={added || selected.length >= 20} onClick={() => add(path)} aria-label={`${added ? '已添加' : '添加'} ${path}`}>{added ? '已添加' : '添加'}</Button></li>;
        })}</ul>
        {selected.length >= 20 && <p className="hint">已达到 20 个文件上限，请先从授权列表移除不需要的路径。</p>}
      </div>}
      <p className="hint">仅列出符合条件的 Git 已跟踪文件；也可在下方手动填写其他现有文件。文件内容是否可读取将在创建任务时核验。</p>
    </details>
  );
}
