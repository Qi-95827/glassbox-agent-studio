package dev.workbench.framework.tool;

import dev.workbench.domain.ToolKind;

/** 内置工具的服务端契约版本；版本不由模型或用户选择。 */
public final class ToolContracts {
  public static final int SIMULATED_VERSION = 1;
  public static final int SINGLE_FILE_VERSION = 1;
  public static final int MULTI_FILE_VERSION = 2;
  public static final int SEARCH_VERSION = 1;

  private ToolContracts() {}

  /** 操作性质由受控工具类型决定。 */
  static ToolEffect effectFor(ToolKind kind) {
    return switch (kind) {
      case SIMULATED_INSPECTION, WORKSPACE_READ, WORKSPACE_SEARCH -> ToolEffect.READ_ONLY;
      case WORKSPACE_WRITE -> ToolEffect.WRITE;
    };
  }

  /** 根据实际将注册的工具类型和授权文件数量选择契约。 */
  public static int versionFor(ToolKind kind, int fileCount) {
    if (kind == null || fileCount < 0 || fileCount > 20)
      throw new IllegalArgumentException("工具契约参数无效。");
    return switch (kind) {
      case SIMULATED_INSPECTION -> SIMULATED_VERSION;
      case WORKSPACE_SEARCH -> {
        if (fileCount == 0) throw new IllegalArgumentException("搜索工具需要授权文件。");
        yield SEARCH_VERSION;
      }
      case WORKSPACE_READ, WORKSPACE_WRITE -> {
        if (fileCount == 0) throw new IllegalArgumentException("文件工具需要授权文件。");
        yield fileCount == 1 ? SINGLE_FILE_VERSION : MULTI_FILE_VERSION;
      }
    };
  }
}
