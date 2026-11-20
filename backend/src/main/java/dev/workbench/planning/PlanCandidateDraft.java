package dev.workbench.planning;

import java.util.List;

/**
 * 模型提出的候选计划结构（协议版本 1）。
 *
 * <p>模型只能提出角色别名、节点目标、依赖、文件子集、产出和验收条件。工具、根目录、 模型凭据、检查命令和审批结果都不在这里，由执行侧按注册表和当前授权决定。
 */
public record PlanCandidateDraft(
    int schemaVersion, List<Node> nodes, List<String> assumptions, List<String> openQuestions) {
  public static final int PROTOCOL_VERSION = 1;

  public PlanCandidateDraft {
    nodes = nodes == null ? List.of() : List.copyOf(nodes);
    assumptions = assumptions == null ? List.of() : List.copyOf(assumptions);
    openQuestions = openQuestions == null ? List.of() : List.copyOf(openQuestions);
  }

  /** 首期只接受三个受信角色别名，重复使用同一角色会装配成不同实例。 */
  public enum Role {
    ANALYZE,
    IMPLEMENT,
    REVIEW
  }

  /** 文本产出没有路径；文件产出的路径必须属于该节点批准的写入范围。 */
  public enum OutputKind {
    TEXT,
    FILE
  }

  public record ExpectedOutput(OutputKind kind, String path, boolean required) {}

  public record Node(
      String key,
      String title,
      Role role,
      String objective,
      List<String> dependsOn,
      List<String> readFiles,
      List<String> writeFiles,
      List<ExpectedOutput> expectedOutputs,
      List<String> acceptanceCriteria,
      String agentRef) {
    public Node {
      dependsOn = dependsOn == null ? List.of() : List.copyOf(dependsOn);
      readFiles = readFiles == null ? List.of() : List.copyOf(readFiles);
      writeFiles = writeFiles == null ? List.of() : List.copyOf(writeFiles);
      expectedOutputs = expectedOutputs == null ? List.of() : List.copyOf(expectedOutputs);
      acceptanceCriteria = acceptanceCriteria == null ? List.of() : List.copyOf(acceptanceCriteria);
      agentRef = agentRef == null || agentRef.isBlank() ? null : agentRef.strip();
    }

    /** 兼容旧协议中没有私有 Agent 建议的节点。 */
    public Node(
        String key,
        String title,
        Role role,
        String objective,
        List<String> dependsOn,
        List<String> readFiles,
        List<String> writeFiles,
        List<ExpectedOutput> expectedOutputs,
        List<String> acceptanceCriteria) {
      this(
          key,
          title,
          role,
          objective,
          dependsOn,
          readFiles,
          writeFiles,
          expectedOutputs,
          acceptanceCriteria,
          null);
    }
  }
}
