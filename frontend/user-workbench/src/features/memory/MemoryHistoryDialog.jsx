import React, { useEffect, useRef, useState } from 'react';
import { Alert, Button, Modal, Spin } from 'antd';

const categories = { PROJECT_CONVENTION: '项目约定', PROJECT_BACKGROUND: '项目背景', PRECAUTION: '注意事项' };

/** 显式按需读取历史正文；版本游标只向过去翻页。 */
export default function MemoryHistoryDialog({ workspaceId, memoryId, api, onClose }) {
  const [items, setItems] = useState([]);
  const [next, setNext] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const mounted = useRef(false);

  async function load(before) {
    setLoading(true);
    setError('');
    try {
      const query = new URLSearchParams({ limit: '20' });
      if (before != null) query.set('beforeVersion', String(before));
      const page = await api(`/workspaces/${workspaceId}/memories/${memoryId}/history?${query}`);
      if (!mounted.current) return;
      setItems(previous => before == null ? page.items : [...previous, ...page.items]);
      setNext(page.nextBeforeVersion);
    } catch (e) {
      if (mounted.current) setError(e.message);
    } finally {
      if (mounted.current) setLoading(false);
    }
  }

  useEffect(() => {
    mounted.current = true;
    load(null);
    return () => { mounted.current = false; };
  }, [workspaceId, memoryId]);

  return (
    <Modal title="项目背景历史" open onCancel={onClose} footer={null} width={700}>
      <Alert type="info" showIcon message="历史版本含原文；查看不会恢复旧版本，也不会撤回已发送给模型的内容。" />
      {error && <Alert type="error" showIcon message={error} />}
      {items.map(item => (
        <div className="agent-card" key={item.version}>
          <div className="agent-text">
            <strong>v{item.version} · {categories[item.category] || item.category} · {item.status === 'DELETED' ? '已删除' : item.status === 'ACTIVE' ? '有效' : '状态未知'}</strong>
            <small>{new Date(item.changedAt).toLocaleString('zh-CN')}</small>
            <small>{item.content}</small>
          </div>
        </div>
      ))}
      {loading && <Spin />}
      {!loading && !error && items.length === 0 && <p>没有保留历史记录。</p>}
      {!loading && error && items.length === 0 && <Button onClick={() => load(null)}>重新加载</Button>}
      {!loading && next != null && <Button onClick={() => load(next)}>加载更早版本</Button>}
    </Modal>
  );
}
