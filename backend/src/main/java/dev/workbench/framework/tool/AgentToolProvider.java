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

  record Scope(
      String workspaceId,
      List<String> authorizedPaths,
      Scenario scenario,
      String locale,
      String runId) {
    /** 旧调用没有任务语言；工具描述回退中文。 */
    public Scope(String workspaceId, List<String> authorizedPaths, Scenario scenario) {
      this(workspaceId, authorizedPaths, scenario, null, null);
    }

    /** 兼容尚未传递运行标识的装配路径；需要运行归属的工具会拒绝装配。 */
    public Scope(
        String workspaceId, List<String> authorizedPaths, Scenario scenario, String locale) {
      this(workspaceId, authorizedPaths, scenario, locale, null);
    }

    public Scope {
      authorizedPaths = List.copyOf(authorizedPaths);
      locale =
          locale == null || locale.isBlank()
              ? "zh-CN"
              : locale.strip().toLowerCase(java.util.Locale.ROOT).startsWith("en")
                  ? "en-US"
                  : "zh-CN";
      runId = runId == null || runId.isBlank() ? null : runId.strip();
    }
  }
}
