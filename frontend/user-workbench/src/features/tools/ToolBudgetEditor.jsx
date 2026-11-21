import BudgetEditor from '../../components/BudgetEditor';

const tools = [
  ['SIMULATED_INSPECTION', '模拟检查工具'],
  ['WORKSPACE_READ', '读取授权文件'],
  ['WORKSPACE_SEARCH', '搜索授权文件'],
  ['WORKSPACE_WRITE', '修改授权文件']
];

export default function ToolBudgetEditor({ value, onChange }) {
  return (
    <BudgetEditor
      title="按工具类型设置调用上限（可选）"
      description="与运行总次数、单工具、单 Agent 和角色限制同时生效；设置上限不会授予工具权限。"
      entries={tools}
      value={value}
      onChange={onChange}
    />
  );
}
