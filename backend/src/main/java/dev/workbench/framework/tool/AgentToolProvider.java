package dev.workbench.framework.tool;

import dev.workbench.domain.Scenario;
import dev.workbench.domain.ToolKind;
import java.util.List;

/** 受信工具提供者只声明业务动作，SDK 注册由项目生成的网关包装器负责。 */
public interface AgentToolProvider {
  ToolKind kind();

  /** 契约版本由服务端依据任务范围确定，不能由模型选择。 */
  int contractVersion(int authorizedFileCount);

  /** 根据任务既有授权构造声明。实际动作在注册后的网关调用中执行。 */
  ToolSpecification<?> create(Scope scope);

  record Scope(String workspaceId, List<String> authorizedPaths, Scenario scenario) {
    public Scope {
      authorizedPaths = List.copyOf(authorizedPaths);
    }
  }
}
