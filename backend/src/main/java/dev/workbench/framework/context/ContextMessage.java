package dev.workbench.framework.context;

/**
 * 与框架无关的一条上下文消息。
 *
 * <p>工具结果使用 {@code TOOL} 角色和可选 {@code toolCallId}；压缩时必须保持工具调用与结果成对， 不能只删除其中一侧。正文始终按不可信数据处理。
 *
 * @param originalIndex 该消息在本次模型请求原始列表中的下标；压缩后可据此精确还原为原始 SDK 对象， {@code null}
 *     表示这是压缩器新生成的消息（摘要检查点或硬保护通知），必须重新构建
 */
public record ContextMessage(
    String role, String name, String content, String toolCallId, Integer originalIndex) {
  public ContextMessage {
    role = role == null || role.isBlank() ? "USER" : role.strip().toUpperCase();
    name = name == null ? null : name;
    content = content == null ? "" : content;
    toolCallId = toolCallId == null || toolCallId.isBlank() ? null : toolCallId;
  }

  /** 压缩器新建消息（无原始下标）。 */
  public ContextMessage(String role, String name, String content, String toolCallId) {
    this(role, name, content, toolCallId, null);
  }

  public ContextMessage(String role, String content) {
    this(role, null, content, null, null);
  }

  public boolean toolResult() {
    return "TOOL".equals(role);
  }

  /** 是否为带工具调用的助手消息：此类消息与随后匹配的 TOOL 结果构成不可分割的原子组。 */
  public boolean toolCall() {
    return "ASSISTANT".equals(role) && toolCallId != null;
  }
}
