import React, { useEffect, useMemo, useState } from 'react';
import {
  Alert, Button, Checkbox, Divider, Input, Select, Space, Spin, Tabs, Tag, Typography
} from 'antd';
import { useAuth } from '../auth/AuthGate.jsx';

const statusLabels = { DRAFT: '草稿', PUBLISHED: '已发布', ARCHIVED: '已归档' };
const roleLabels = { ANALYST: '分析（只读）', REVIEWER: '审查（无工具）' };

const emptySkill = { name: '', summary: '', usageNotes: '', inputContract: '', outputContract: '', contentMd: '' };
const emptyTool = { name: '', description: '', executorKind: 'SEARCH', fileExtensions: '.jsx, .tsx' };
const emptyAgent = {
  name: '', description: '', responsibility: '', outputRequirements: '',
  permissionRole: 'ANALYST', recommendable: true, modelStrategy: 'INHERIT_TASK', modelMode: 'mock',
  skillIds: [], toolIds: [], skillPins: {}, toolPins: {}
};

/** 「我的 Agent」：Skill、只读工具、Agent 草稿与版本。 */
export default function MyAgentsPage({ api }) {
  const { user } = useAuth();
  const [skills, setSkills] = useState([]);
  const [tools, setTools] = useState([]);
  const [agents, setAgents] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [message, setMessage] = useState('');
  const [refreshing, setRefreshing] = useState(0);

  const reload = () => setRefreshing(value => value + 1);
  useEffect(() => {
    let active = true;
    setLoading(true);
    Promise.all([api('/my/skills'), api('/my/tools'), api('/my/agents')])
      .then(([nextSkills, nextTools, nextAgents]) => {
        if (!active) return;
        setSkills(nextSkills);
        setTools(nextTools);
        setAgents(nextAgents);
        setError('');
      })
      .catch(failure => active && setError(failure.message))
      .finally(() => active && setLoading(false));
    return () => { active = false; };
  }, [refreshing]);

  const act = async (promise, success) => {
    setError('');
    try {
      await promise;
      setMessage(success);
      reload();
    } catch (failure) {
      setError(failure.message);
    }
  };

  if (loading) return <Spin tip="正在读取我的组件" />;
  return <div>
    <Typography.Title level={4} style={{ marginTop: 0 }}>我的 Agent</Typography.Title>
    <Typography.Paragraph type="secondary">
      当前账号：{user.displayName}（{user.username}）。私有 Skill、工具和 Agent 只对当前账号可见；
      发布后固定版本，旧运行继续使用发布时的组件。
    </Typography.Paragraph>
    {error && <Alert type="error" showIcon message={error} style={{ marginBottom: 12 }} />}
    {message && <Alert type="success" showIcon message={message} style={{ marginBottom: 12 }} />}
    <Tabs items={[
      { key: 'agents', label: `我的 Agent（${agents.length}）`, children:
        <AgentTab api={api} agents={agents} skills={skills} tools={tools} act={act} /> },
      { key: 'skills', label: `我的 Skill（${skills.length}）`, children:
        <SkillTab api={api} rows={skills} act={act} /> },
      { key: 'tools', label: `我的工具（${tools.length}）`, children:
        <ToolTab api={api} rows={tools} act={act} /> },
      ...(user.role === 'ADMIN' ? [{ key: 'accounts', label: '账号与归属', children:
        <AccountsTab api={api} current={user} act={act} /> }] : [])
    ]} />
  </div>;
}

function SkillTab({ api, rows, act }) {
  const [form, setForm] = useState(emptySkill);
  const [editing, setEditing] = useState(null);
  const [versions, setVersions] = useState(null);
  const save = () => act(
    editing
      ? api(`/my/skills/${editing}`, { method: 'PUT', body: JSON.stringify(form) })
      : api('/my/skills', { method: 'POST', body: JSON.stringify(form) }),
    editing ? 'Skill 草稿已更新' : 'Skill 草稿已创建'
  ).then(() => { setEditing(null); setForm(emptySkill); });
  return <Space direction="vertical" size="middle" style={{ width: '100%' }}>
    <Typography.Text strong>{editing ? '编辑 Skill 草稿' : '新建 Skill'}</Typography.Text>
    <Input placeholder="名称，例如 前端交付检查" value={form.name} onChange={e => setForm({ ...form, name: e.target.value })} />
    <Input placeholder="用途摘要" value={form.summary} onChange={e => setForm({ ...form, summary: e.target.value })} />
    <Input placeholder="输入约定（可选）" value={form.inputContract} onChange={e => setForm({ ...form, inputContract: e.target.value })} />
    <Input placeholder="预期输出（可选）" value={form.outputContract} onChange={e => setForm({ ...form, outputContract: e.target.value })} />
    <Input.TextArea rows={6} placeholder="Markdown 指导正文；导入时可直接粘贴 .md 内容" value={form.contentMd}
      onChange={e => setForm({ ...form, contentMd: e.target.value })} />
    <Space>
      <Button type="primary" onClick={save}>{editing ? '保存草稿' : '创建草稿'}</Button>
      {editing && <Button onClick={() => { setEditing(null); setForm(emptySkill); }}>取消</Button>}
    </Space>
    <Divider />
    {rows.length === 0 && <Alert type="info" message="还没有私有 Skill。" />}
    {rows.map(row => <div key={row.id} style={{ border: '1px solid #dde4ee', borderRadius: 10, padding: 12, marginBottom: 10 }}>
      <Space wrap>
        <Typography.Text strong>{row.name}</Typography.Text>
        <Tag>{statusLabels[row.status] || row.status}</Tag>
        <Tag color="blue">v{row.currentVersion}</Tag>
        {row.draftChanged && <Tag color="orange">草稿有未发布修改</Tag>}
      </Space>
      <div><Typography.Text type="secondary">{row.summary}</Typography.Text></div>
      <pre style={{ whiteSpace: 'pre-wrap', margin: '8px 0', maxHeight: 120, overflow: 'auto' }}>{row.contentMd}</pre>
      <Space wrap>
        <Button size="small" onClick={() => { setEditing(row.id); setForm({ ...emptySkill, ...row }); }}>编辑</Button>
        <Button size="small" type="primary" onClick={() => act(api(`/my/skills/${row.id}/publish`, { method: 'POST' }), 'Skill 已发布新版本')}>发布</Button>
        <Button size="small" onClick={() => setVersions({ id: row.id, type: 'skill' })}>版本</Button>
        <Button size="small" danger onClick={() => act(api(`/my/skills/${row.id}/archive`, { method: 'POST' }), 'Skill 已归档')}>归档</Button>
      </Space>
    </div>)}
    {versions && <VersionDialog api={api} target={versions} onClose={() => setVersions(null)} />}
  </Space>;
}

function ToolTab({ api, rows, act }) {
  const [form, setForm] = useState(emptyTool);
  const [editing, setEditing] = useState(null);
  const [versions, setVersions] = useState(null);
  const save = () => {
    const body = {
      ...form,
      fileExtensions: form.fileExtensions.split(',').map(item => item.trim()).filter(Boolean)
    };
    return act(
      editing
        ? api(`/my/tools/${editing}`, { method: 'PUT', body: JSON.stringify(body) })
        : api('/my/tools', { method: 'POST', body: JSON.stringify(body) }),
      editing ? '工具草稿已更新' : '工具草稿已创建'
    ).then(() => { setEditing(null); setForm(emptyTool); });
  };
  return <Space direction="vertical" size="middle" style={{ width: '100%' }}>
    <Alert type="info" showIcon message="首批私有工具只使用受信只读执行器；扩展名过滤在任务与节点的授权文件范围内再次收窄，不能扩大文件权限。" />
    <Input placeholder="工具名称，例如 前端标签搜索" value={form.name} onChange={e => setForm({ ...form, name: e.target.value })} />
    <Input placeholder="说明" value={form.description} onChange={e => setForm({ ...form, description: e.target.value })} />
    <Select value={form.executorKind} onChange={value => setForm({ ...form, executorKind: value })}
      options={[{ value: 'SEARCH', label: '授权搜索（固定查询方式）' }, { value: 'READ', label: '授权读取' }]} />
    <Input placeholder="允许的扩展名，例如 .jsx, .tsx" value={form.fileExtensions}
      onChange={e => setForm({ ...form, fileExtensions: e.target.value })} />
    <Space>
      <Button type="primary" onClick={save}>{editing ? '保存草稿' : '创建草稿'}</Button>
      {editing && <Button onClick={() => { setEditing(null); setForm(emptyTool); }}>取消</Button>}
    </Space>
    <Divider />
    {rows.map(row => <div key={row.id} style={{ border: '1px solid #dde4ee', borderRadius: 10, padding: 12, marginBottom: 10 }}>
      <Space wrap>
        <Typography.Text strong>{row.name}</Typography.Text>
        <Tag>{statusLabels[row.status] || row.status}</Tag>
        <Tag color="blue">v{row.currentVersion}</Tag>
        <Tag>{row.executorLabel}</Tag>
      </Space>
      <div><Typography.Text type="secondary">{row.description}</Typography.Text></div>
      <div>允许扩展名：{(row.fileExtensions || []).join(', ') || '未设置'}</div>
      <Space wrap style={{ marginTop: 8 }}>
        <Button size="small" onClick={() => {
          setEditing(row.id);
          setForm({ ...emptyTool, ...row, fileExtensions: (row.fileExtensions || []).join(', ') });
        }}>编辑</Button>
        <Button size="small" type="primary" onClick={() => act(api(`/my/tools/${row.id}/publish`, { method: 'POST' }), '工具已发布新版本')}>发布</Button>
        <Button size="small" onClick={() => setVersions({ id: row.id, type: 'tool' })}>版本</Button>
        <Button size="small" danger onClick={() => act(api(`/my/tools/${row.id}/archive`, { method: 'POST' }), '工具已归档')}>归档</Button>
      </Space>
    </div>)}
    {versions && <VersionDialog api={api} target={versions} onClose={() => setVersions(null)} />}
  </Space>;
}

function AgentTab({ api, agents, skills, tools, act }) {
  const [form, setForm] = useState(emptyAgent);
  const [editing, setEditing] = useState(null);
  const [idea, setIdea] = useState('');
  const [ideaMode, setIdeaMode] = useState('LOCAL');
  const [versions, setVersions] = useState(null);
  const publishedSkills = useMemo(() => skills.filter(row => row.status === 'PUBLISHED' && row.currentVersion > 0), [skills]);
  const publishedTools = useMemo(() => tools.filter(row => row.status === 'PUBLISHED' && row.currentVersion > 0), [tools]);

  const save = () => {
    const body = {
      name: form.name, description: form.description, responsibility: form.responsibility,
      outputRequirements: form.outputRequirements, permissionRole: form.permissionRole,
      recommendable: form.recommendable, modelStrategy: form.modelStrategy, modelMode: form.modelMode,
      skills: form.skillIds.map(id => ({ id, version: form.skillPins?.[id] ?? null })),
      tools: form.toolIds.map(id => ({ id, version: form.toolPins?.[id] ?? null }))
    };
    return act(
      editing
        ? api(`/my/agents/${editing}`, { method: 'PUT', body: JSON.stringify(body) })
        : api('/my/agents', { method: 'POST', body: JSON.stringify(body) }),
      editing ? 'Agent 草稿已更新' : 'Agent 草稿已创建'
    ).then(() => { setEditing(null); setForm(emptyAgent); });
  };
  const draft = async () => {
    setErrorSafe('');
    try {
      const result = await api('/my/agents/draft', { method: 'POST', body: JSON.stringify({ idea, mode: ideaMode }) });
      const skillPins = Object.fromEntries((result.suggestedSkills || []).map(item => [item.id, item.version]));
      const toolPins = Object.fromEntries((result.suggestedTools || []).map(item => [item.id, item.version]));
      setForm({
        ...emptyAgent,
        ...result,
        skillIds: Object.keys(skillPins),
        toolIds: Object.keys(toolPins),
        skillPins,
        toolPins
      });
      if ((result.suggestedSkills?.length || 0) + (result.suggestedTools?.length || 0) > 0)
        alert('模型已提出 Skill/工具搭配建议并预选，请检查后再保存或发布。');
    } catch (failure) {
      alert(failure.message);
    }
  };
  function setErrorSafe() { /* 草稿失败由页面统一提示 */ }

  return <Space direction="vertical" size="middle" style={{ width: '100%' }}>
    <Alert type="info" showIcon message="从设计思路生成的是可编辑草稿；本地规则不调用模型，模型生成会把设计思路发送给已配置的 DeepSeek。无论哪种方式都不会直接发布或执行。" />
    <Input.TextArea rows={2} value={idea} onChange={e => setIdea(e.target.value)}
      placeholder="例如：检查前端页面的交互和可访问性，只搜索 .jsx/.tsx 文件，列出覆盖和遗漏" />
    <Space>
      <Select value={ideaMode} onChange={setIdeaMode} style={{ width: 190 }}
        options={[{ value: 'LOCAL', label: '本地规则（不调用模型）' }, { value: 'MODEL', label: '模型生成（DeepSeek）' }]} />
      <Button onClick={draft}>从思路生成草稿</Button>
    </Space>
    <Divider />
    <Typography.Text strong>{editing ? '编辑 Agent 草稿' : '手动组装 Agent'}</Typography.Text>
    <Input placeholder="名称" value={form.name} onChange={e => setForm({ ...form, name: e.target.value })} />
    <Input placeholder="简介" value={form.description} onChange={e => setForm({ ...form, description: e.target.value })} />
    <Input.TextArea rows={3} placeholder="职责（装配为系统职责，不授予工具）" value={form.responsibility}
      onChange={e => setForm({ ...form, responsibility: e.target.value })} />
    <Input.TextArea rows={2} placeholder="输出要求" value={form.outputRequirements}
      onChange={e => setForm({ ...form, outputRequirements: e.target.value })} />
    <Select value={form.permissionRole} onChange={value => setForm({ ...form, permissionRole: value })}
      options={Object.entries(roleLabels).map(([value, label]) => ({ value, label }))} />
    <Select value={form.modelStrategy} onChange={value => setForm({ ...form, modelStrategy: value })}
      options={[{ value: 'INHERIT_TASK', label: '模型策略：继承任务' }, { value: 'FIXED', label: '模型策略：指定已配置模型' }]} />
    {form.modelStrategy === 'FIXED' && <Select value={form.modelMode} onChange={value => setForm({ ...form, modelMode: value })}
      options={[{ value: 'mock', label: '模拟模型' }, { value: 'deepseek', label: 'DeepSeek Flash（真实调用）' }]} />}
    <Checkbox checked={form.recommendable} onChange={e => setForm({ ...form, recommendable: e.target.checked })}>
      允许规划器在任务勾选后推荐
    </Checkbox>
    <div>Skill（发布时固定版本）</div>
    <Select mode="multiple" value={form.skillIds} style={{ width: '100%' }}
      onChange={value => setForm(previous => ({
        ...previous,
        skillIds: value,
        skillPins: Object.fromEntries(value.map(id => [id, previous.skillPins?.[id] ?? null]))
      }))}
      options={publishedSkills.map(row => ({ value: row.id, label: `${row.name} v${form.skillPins?.[row.id] ?? row.currentVersion}` }))} />
    <div>只读工具（发布时固定版本）</div>
    <Select mode="multiple" value={form.toolIds} style={{ width: '100%' }}
      onChange={value => setForm(previous => ({
        ...previous,
        toolIds: value,
        toolPins: Object.fromEntries(value.map(id => [id, previous.toolPins?.[id] ?? null]))
      }))}
      options={publishedTools.map(row => ({ value: row.id, label: `${row.name} v${form.toolPins?.[row.id] ?? row.currentVersion}` }))} />
    <Typography variant="caption" color="text.secondary">
      固定版本：{form.skillIds.map(id => `${id.slice(0, 8)} v${form.skillPins?.[id] ?? '保存时最新'}`).join('、') || '无 Skill'}
      {'；'}{form.toolIds.map(id => `${id.slice(0, 8)} v${form.toolPins?.[id] ?? '保存时最新'}`).join('、') || '无工具'}
    </Typography>
    <Space>
      <Button type="primary" onClick={save}>{editing ? '保存草稿' : '创建草稿'}</Button>
      {editing && <Button onClick={() => { setEditing(null); setForm(emptyAgent); }}>取消</Button>}
    </Space>
    <Divider />
    {agents.map(row => <div key={row.id} style={{ border: '1px solid #dde4ee', borderRadius: 10, padding: 12, marginBottom: 10 }}>
      <Space wrap>
        <Typography.Text strong>{row.name}</Typography.Text>
        <Tag>{statusLabels[row.status] || row.status}</Tag>
        <Tag color="blue">v{row.currentVersion}</Tag>
        <Tag>{roleLabels[row.permissionRole] || row.permissionRole}</Tag>
        <Tag>{row.modelStrategy === 'FIXED' ? `固定模型 ${row.modelMode}` : '继承任务模型'}</Tag>
        {row.recommendable && <Tag color="green">可推荐</Tag>}
      </Space>
      <div><Typography.Text type="secondary">{row.description}</Typography.Text></div>
      <Typography.Paragraph style={{ whiteSpace: 'pre-wrap', marginBottom: 4 }}>{row.responsibility}</Typography.Paragraph>
      <div>定义引用：{row.definitionRef} · 固定摘要 {row.specSha256 ? row.specSha256.slice(0, 12) + '…' : '未发布'}</div>
      <Space wrap style={{ marginTop: 8 }}>
        <Button size="small" onClick={() => {
          setEditing(row.id);
          setForm({
            ...emptyAgent, ...row,
            modelStrategy: row.modelStrategy || 'INHERIT_TASK',
            modelMode: row.modelMode || 'mock',
            skillIds: (row.skills || []).map(item => item.id),
            toolIds: (row.tools || []).map(item => item.id),
            skillPins: Object.fromEntries((row.skills || []).map(item => [item.id, item.version])),
            toolPins: Object.fromEntries((row.tools || []).map(item => [item.id, item.version]))
          });
        }}>编辑</Button>
        <Button size="small" type="primary" onClick={() => act(api(`/my/agents/${row.id}/publish`, { method: 'POST' }), 'Agent 已发布新版本')}>发布</Button>
        <Button size="small" onClick={() => setVersions({ id: row.id, type: 'agent' })}>版本</Button>
        <Button size="small" danger onClick={() => act(api(`/my/agents/${row.id}/archive`, { method: 'POST' }), 'Agent 已归档')}>归档</Button>
      </Space>
    </div>)}
    {versions && <VersionDialog api={api} target={versions} onClose={() => setVersions(null)} />}
  </Space>;
}

function VersionDialog({ api, target, onClose }) {
  const [rows, setRows] = useState(null);
  useEffect(() => {
    api(`/my/${target.type}s/${target.id}/versions`).then(setRows).catch(() => setRows([]));
  }, []);
  return <div style={{ border: '1px dashed #9aa7b8', padding: 12, marginTop: 8 }}>
    <Space><Typography.Text strong>已发布版本</Typography.Text><Button size="small" onClick={onClose}>关闭</Button></Space>
    {!rows ? <Spin size="small" /> : rows.length === 0 ? <div>暂无已发布版本。</div> : rows.map(row =>
      <div key={row.version}>
        v{row.version} · {row.name} · {row.specSha256 ? row.specSha256.slice(0, 12) + '…' : row.createdAt}
        {row.modelStrategy === 'FIXED' && <span> · 固定模型 {row.modelMode}</span>}
        {row.skills && <div style={{ marginLeft: 12 }}>
          Skill：{row.skills.map(item => `${item.name} v${item.version}`).join('、') || '无'}；
          工具：{row.tools.map(item => `${item.name} v${item.version}`).join('、') || '无'}
        </div>}
      </div>)}
  </div>;
}

function AccountsTab({ api, current, act }) {
  const [users, setUsers] = useState([]);
  const [form, setForm] = useState({ username: '', password: '', displayName: '', role: 'USER' });
  const [grants, setGrants] = useState([]);
  const [grantUser, setGrantUser] = useState(null);
  const [grantPath, setGrantPath] = useState('');
  const load = () => api('/auth/users').then(setUsers).catch(() => setUsers([]));
  const loadGrants = () => api('/admin/repositories').then(setGrants).catch(() => setGrants([]));
  useEffect(() => { load(); loadGrants(); }, []);
  return <Space direction="vertical" size="middle" style={{ width: '100%' }}>
    <Alert type="info" showIcon message="普通用户只能管理自己的资源。管理员添加账号后，用户使用该账号登录；密码不会回显。" />
    {!current.legacyClaimed && <Button onClick={() => act(api('/auth/claim-legacy', { method: 'POST' }), '旧 LOCAL 历史数据已归入当前管理员').then(load)}>
      领取旧 LOCAL 历史数据
    </Button>}
    <Input placeholder="新账号（3-32 位小写字母或数字）" value={form.username} onChange={e => setForm({ ...form, username: e.target.value })} />
    <Input placeholder="显示名称" value={form.displayName} onChange={e => setForm({ ...form, displayName: e.target.value })} />
    <Input.Password placeholder="初始密码，至少 8 位" value={form.password} onChange={e => setForm({ ...form, password: e.target.value })} />
    <Select value={form.role} onChange={value => setForm({ ...form, role: value })}
      options={[{ value: 'USER', label: '普通用户' }, { value: 'ADMIN', label: '管理员' }]} />
    <Button type="primary" onClick={() => act(api('/auth/users', { method: 'POST', body: JSON.stringify(form) }), '账号已添加')
      .then(() => setForm({ username: '', password: '', displayName: '', role: 'USER' })).then(load)}>添加用户</Button>
    <Divider />
    {users.map(row => <div key={row.id}>
      {row.displayName}（{row.username}）· {row.role} · {row.legacyClaimed ? '已领取旧历史' : '未领取旧历史'}
    </div>)}
    <Divider />
    <Typography.Text strong>仓库导入授权</Typography.Text>
    <Alert type="info" showIcon message="普通账号导入本地仓库前必须由管理员授权规范化仓库根目录；未授权时工作区创建会被服务端拒绝。撤销的是「导入授权」：已有工作区仍可按原归属访问，新导入会被拒绝。" />
    <Space wrap>
      <Select placeholder="选择账号" value={grantUser} onChange={setGrantUser} style={{ width: 180 }}
        options={users.map(row => ({ value: row.username, label: `${row.displayName}（${row.role}）` }))} />
      <Input placeholder="仓库根目录，例如 D:\\projects\\app" value={grantPath}
        onChange={event => setGrantPath(event.target.value)} style={{ width: 360 }} />
      <Button onClick={() => act(api('/admin/repositories', {
        method: 'POST', body: JSON.stringify({ username: grantUser, repositoryPath: grantPath })
      }), '仓库授权已登记').then(loadGrants)}>授权仓库</Button>
    </Space>
    {grants.map(row => <div key={row.id}>
      {row.username} · {row.repositoryRoot} · 授权人 {row.grantedBy}
      <Button size="small" danger style={{ marginLeft: 8 }} onClick={() =>
        act(api(`/admin/repositories/${row.id}`, { method: 'DELETE' }), '导入授权已撤销；已有工作区仍保留访问').then(loadGrants)}>撤销导入授权</Button>
    </div>)}
  </Space>;
}
