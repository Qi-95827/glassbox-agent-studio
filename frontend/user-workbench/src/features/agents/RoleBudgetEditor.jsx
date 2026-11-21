import BudgetEditor from '../../components/BudgetEditor';

const roles = [
  ['ANALYST', '分析 Agent'],
  ['CODER', '开发 Agent'],
  ['REVIEWER', '审查 Agent']
];

export default function RoleBudgetEditor({ value, onChange }) {
  return (
    <BudgetEditor
      title="按角色设置单实例上限（可选）"
      description="与统一单 Agent 和运行总上限同时生效；不会授予角色额外权限。"
      entries={roles}
      value={value}
      onChange={onChange}
    />
  );
}
