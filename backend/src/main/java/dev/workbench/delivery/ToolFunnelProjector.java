package dev.workbench.delivery;

import dev.workbench.domain.ToolKind;
import dev.workbench.framework.agent.assembly.AgentAssemblySnapshot;
import dev.workbench.framework.agent.definition.AgentDefinition;
import dev.workbench.framework.workflow.plan.PlanNode;
import java.util.ArrayList;
import java.util.List;

/**
 * 工具权限漏斗的真实投影：已授予的工具与平台按角色/节点/写授权裁剪掉的工具。
 *
 * <p>此前前端用一份硬编码清单反推「被裁剪」，其中还写错了枚举名，于是界面会展示一个根本不存在的工具。
 * 这里以装配快照里的真实 {@code Map<ToolKind,Integer>} 与 Agent 定义声明的工具为准，逐项给出原因编码，
 * 前端只负责翻译，不再自行猜测。
 */
final class ToolFunnelProjector {
  /** 裁剪原因编码；前端按编码翻译，服务端不输出自然语言，避免双语文案漂移。 */
  private ToolFunnelProjector() {}

  static ExecutionViewService.ToolFunnelView project(
      PlanNode node, AgentAssemblySnapshot snapshot, AgentDefinition definition) {
    return project(node, snapshot, definition, null);
  }

  /** 与前端 {@code latestAssemblyAttempt} 使用同一规则：最近一次拥有装配快照的尝试。 */
  static ExecutionViewService.ToolFunnelView project(
      PlanNode node,
      AgentAssemblySnapshot snapshot,
      AgentDefinition definition,
      Integer attemptNumber) {
    if (node == null || snapshot == null || definition == null) return null;
    var components = snapshot.components();
    var granted = components.tools();
    var declared = definition.tools();
    boolean writeAuthorized =
        components.authorization() != null && components.authorization().writeAuthorized();
    var grants = new ArrayList<ExecutionViewService.ToolGrantView>();
    var filtered = new ArrayList<ExecutionViewService.ToolFilterView>();
    for (ToolKind kind : ToolKind.values()) {
      Integer version = granted.get(kind);
      if (version != null) {
        grants.add(new ExecutionViewService.ToolGrantView(kind.name(), version));
        continue;
      }
      if (!declared.contains(kind)) {
        filtered.add(new ExecutionViewService.ToolFilterView(kind.name(), "NOT_DECLARED_BY_DEFINITION"));
        continue;
      }
      String reason;
      if (kind == ToolKind.WORKSPACE_WRITE && !node.workspaceWrite()) {
        reason = "NODE_NOT_WRITE_AUTHORIZED";
      } else if (kind == ToolKind.WORKSPACE_WRITE && !writeAuthorized) {
        reason = "RUN_NOT_WRITE_AUTHORIZED";
      } else if ((kind == ToolKind.WORKSPACE_READ || kind == ToolKind.WORKSPACE_SEARCH)
          && !node.workspaceRead()) {
        reason = "NODE_NOT_WORKSPACE_READ";
      } else {
        reason = "RUN_POLICY_FILTERED";
      }
      filtered.add(new ExecutionViewService.ToolFilterView(kind.name(), reason));
    }
    return new ExecutionViewService.ToolFunnelView(
        components.definition().id(),
        components.definition().version(),
        List.copyOf(grants),
        List.copyOf(filtered),
        attemptNumber);
  }
}
