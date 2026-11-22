package dev.workbench.framework.context;

/**
 * 与框架无关的一条上下文消息。
 *
 * <p>工具结果使用 {@code TOOL} 角色和可选 {@code toolCallId}；压缩时必须保持工具调用与结果成对，
 * 不能只删除其中一侧。正文始终按不可信数据处理。
 */
public record ContextMessage(String role, String name, String content, String toolCallId) {
  public ContextMessage {
    role = role == null || role.isBlank() ? "USER" : role.strip().toUpperCase();
    name = name == null ? null : name;
    content = content == null ? "" : content;
    toolCallId = toolCallId == null || toolCallId.isBlank() ? null : toolCallId;
  }

  public ContextMessage(String role, String content) {
    this(role, null, content, null);
  }

  public boolean toolResult() {
    return "TOOL".equals(role);
  }
}
