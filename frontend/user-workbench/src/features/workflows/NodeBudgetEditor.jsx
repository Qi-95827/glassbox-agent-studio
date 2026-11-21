import BudgetEditor from '../../components/BudgetEditor';

const stepsByTemplate = {
  'dual-analysis': [
    ['requirements', '需求与验收分析'],
    ['constraints', '约束与风险分析'],
    ['synthesis', '汇总两份分析']
  ],
  'workspace-analysis': [
    ['inspect', '分析授权文件'],
    ['summarize', '汇总发现与证据']
  ],
  'workspace-change': [
    ['analyze', '分析授权文件'],
    ['edit', '按授权修改'],
    ['review', '独立审查修改证据']
  ],
  'bug-fix': [
    ['analyze', '定位失败原因'],
    ['fix', '按授权修复'],
    ['review', '独立审查修复证据']
  ]
};

export default function NodeBudgetEditor({ template, value, onChange }) {
  const entries = stepsByTemplate[template];
  if (!entries) return null;
  return (
    <BudgetEditor
      title="按节点设置调用上限（可选）"
      description="同一节点的多次尝试共享限额，同时受运行总上限约束。"
      entries={entries}
      value={value}
      onChange={onChange}
    />
  );
}
