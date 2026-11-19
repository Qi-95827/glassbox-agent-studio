import React, { useEffect, useState } from 'react';
import { App, Button, Input, Tag } from 'antd';

const deliveryStatusNames = {
  PENDING: '待批准',
  APPROVED: '已批准',
  REJECTED: '已拒绝',
  STARTED: '执行中',
  CONFIRMED: '已确认',
  CONFLICT: '冲突，需核对',
  UNKNOWN: '结果未知，需核对',
};

/** 工作区交付：未跟踪文件复核、新建文件、推送与合并。所有动作由用户显式触发。 */
export default function WorkspaceDeliveryPanel({ workspace, api, onChanged }) {
  const { message } = App.useApp();
  const [untracked, setUntracked] = useState([]);
  const [deliveries, setDeliveries] = useState([]);
  const [newPath, setNewPath] = useState('');
  const [newContent, setNewContent] = useState('');
  const [remote, setRemote] = useState('origin');
  const [target, setTarget] = useState('main');
  const [note, setNote] = useState('');
  const [busy, setBusy] = useState(false);

  async function refresh() {
    if (!workspace?.id) return;
    try {
      const [files, requests] = await Promise.all([
        api(`/workspaces/${workspace.id}/untracked`),
        api(`/workspaces/${workspace.id}/deliveries`),
      ]);
      setUntracked(files || []);
      setDeliveries(requests || []);
    } catch (error) {
      message.error(error.message);
    }
  }

  useEffect(() => {
    setUntracked([]);
    setDeliveries([]);
    setNewContent('');
    setNewPath('');
    refresh();
  }, [workspace?.id]);

  async function createFile() {
    if (!newPath.trim() || !newContent.trim()) {
      message.warning('请填写新文件路径和内容');
      return;
    }
    setBusy(true);
    try {
      await api(`/workspaces/${workspace.id}/file`, {
        method: 'POST',
        body: JSON.stringify({ path: newPath.trim(), content: newContent }),
      });
      setNewPath('');
      setNewContent('');
      await refresh();
      onChanged?.();
      message.success('新文件已创建并登记');
    } catch (error) {
      message.error(error.message);
    } finally {
      setBusy(false);
    }
  }

  async function review(file) {
    setBusy(true);
    try {
      await api(`/workspaces/${workspace.id}/untracked/review`, {
        method: 'POST',
        body: JSON.stringify({ path: file.path, sha256: file.sha256 }),
      });
      await refresh();
      onChanged?.();
      message.success('已复核未跟踪文件');
    } catch (error) {
      message.error(error.message);
    } finally {
      setBusy(false);
    }
  }

  async function prepare(kind) {
    setBusy(true);
    try {
      await api(`/workspaces/${workspace.id}/deliveries`, {
        method: 'POST',
        body: JSON.stringify({ kind, remote, target, note }),
      });
      await refresh();
      message.success(kind === 'PUSH' ? '已准备推送，请核对并批准' : '已准备合并，请核对并批准');
    } catch (error) {
      message.error(error.message);
    } finally {
      setBusy(false);
    }
  }

  async function decide(id, approve) {
    setBusy(true);
    try {
      await api(`/workspaces/deliveries/${id}/decision`, {
        method: 'POST',
        body: JSON.stringify({ approve }),
      });
      await refresh();
      message.success(approve ? '已批准' : '已拒绝');
    } catch (error) {
      message.error(error.message);
    } finally {
      setBusy(false);
    }
  }

  async function execute(id) {
    setBusy(true);
    try {
      const result = await api(`/workspaces/deliveries/${id}/execute`, { method: 'POST' });
      await refresh();
      onChanged?.();
      if (result.status === 'CONFIRMED') message.success('交付已确认');
      else if (result.status === 'CONFLICT') message.warning('存在冲突或远端拒绝，请核对');
      else message.warning('结果未知，请核对远端或工作区');
    } catch (error) {
      message.error(error.message);
    } finally {
      setBusy(false);
    }
  }

  if (!workspace) return null;

  return (
    <div className="result-box">
      <strong>未跟踪文件与 Git 交付</strong>
      <p className="hint">
        未跟踪文件必须逐项复核后才能进入审批和提交；推送、合并绑定当前 HEAD 与目标分支，冲突或未知结果不会自动重试。
      </p>
      <div className="form-label">新建文本文件（工作区相对路径）</div>
      <Input
        value={newPath}
        onChange={(event) => setNewPath(event.target.value)}
        placeholder="例如 notes/verification.txt"
        maxLength={200}
      />
      <Input.TextArea
        value={newContent}
        onChange={(event) => setNewContent(event.target.value)}
        rows={3}
        maxLength={20000}
        placeholder="文件内容"
      />
      <Button style={{ marginTop: 8 }} loading={busy} onClick={createFile}>
        创建并登记
      </Button>

      <div className="form-label">未跟踪文件</div>
      {untracked.length === 0 ? (
        <p className="hint">没有未跟踪文件。</p>
      ) : (
        untracked.map((file) => (
          <div className="agent-card" key={file.path}>
            <div className="agent-text">
              <strong>{file.path}</strong>
              <small>
                {file.size} 字节 · {file.sha256?.slice(0, 12)} ·{' '}
                {file.declared ? `已复核（${file.declarationSource || '已声明'}）` : '未复核'}
              </small>
            </div>
            {!file.declared && (
              <Button size="small" loading={busy} onClick={() => review(file)}>
                复核
              </Button>
            )}
          </div>
        ))
      )}

      <div className="form-label">推送或合并</div>
      <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
        <Input
          style={{ width: 120 }}
          value={remote}
          onChange={(event) => setRemote(event.target.value)}
          placeholder="远端"
        />
        <Input
          style={{ width: 180 }}
          value={target}
          onChange={(event) => setTarget(event.target.value)}
          placeholder="目标分支"
        />
        <Input
          style={{ width: 220 }}
          value={note}
          onChange={(event) => setNote(event.target.value)}
          placeholder="说明（可选）"
        />
        <Button loading={busy} onClick={() => prepare('PUSH')}>
          准备推送
        </Button>
        <Button loading={busy} onClick={() => prepare('MERGE')}>
          准备合并
        </Button>
      </div>
      {deliveries.map((request) => (
        <div className="agent-card" key={request.id}>
          <div className="agent-text">
            <strong>
              {request.kind} · {request.remote}:{request.target}
            </strong>
            <small>
              {deliveryStatusNames[request.status] || request.status} · HEAD{' '}
              {request.headCommit?.slice(0, 8)} · {request.sourceBranch}
              {request.targetCommit ? ` · 目标 ${request.targetCommit.slice(0, 8)}` : ''}
            </small>
            {request.resultDetail && <small>{request.resultDetail}</small>}
          </div>
          <Tag>{request.status}</Tag>
          {request.status === 'PENDING' && (
            <>
              <Button size="small" onClick={() => decide(request.id, true)}>
                批准
              </Button>
              <Button size="small" onClick={() => decide(request.id, false)}>
                拒绝
              </Button>
            </>
          )}
          {request.status === 'APPROVED' && (
            <Button size="small" loading={busy} onClick={() => execute(request.id)}>
              执行
            </Button>
          )}
        </div>
      ))}
    </div>
  );
}
