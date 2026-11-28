package dev.workbench.framework.agent.assembly;

import dev.workbench.domain.AgentRole;
import dev.workbench.domain.ToolKind;
import dev.workbench.framework.agent.definition.AgentDefinitionRef;
import dev.workbench.framework.context.AgentInput;
import dev.workbench.framework.control.AgentBudgetPolicy;
import dev.workbench.framework.model.AgentModelRegistry.ModelReference;
import dev.workbench.framework.skill.SkillRef;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** 可持久化的实际装配记录。内容来自框架装配及控制器，不保存模型输入或凭据。 */
public record AgentAssemblySnapshot(int version, Components components, AgentBudgetPolicy budget) {
  public static final int VERSION = 1;

  public record PrivateTool(
      String id, int version, String name, String toolName, List<String> fileExtensions) {
    public PrivateTool {
      fileExtensions = fileExtensions == null ? List.of() : List.copyOf(fileExtensions);
    }
  }

  public AgentAssemblySnapshot {
    if (version != VERSION) throw new IllegalArgumentException("不支持的装配快照版本。");
    Objects.requireNonNull(components);
    Objects.requireNonNull(budget);
  }

  /**
   * 单个外部 MCP 工具的真实准入结论。
   *
   * <p>落盘的是装配时的实际决定：成功注册为 {@code allowed=true} 且 {@code reasonCode} 为空；被角色策略剥离或与内置工具
   * 同名跳过时为 {@code allowed=false} 并带稳定原因编码。{@code components} 位于 framework 包，因此这里只保存
   * 纯数据，不依赖 {@code infrastructure} 的准入实现。
   */
  public record McpToolDecision(
      String exposedName, String riskLevel, boolean allowed, String reasonCode) {
    public McpToolDecision {
      exposedName = exposedName == null ? "" : exposedName;
      riskLevel = riskLevel == null ? "HIGH" : riskLevel.strip().toUpperCase(java.util.Locale.ROOT);
      reasonCode = reasonCode == null ? "" : reasonCode;
    }
  }

  public record Components(
      AgentDefinitionRef definition,
      String definitionSha256,
      ModelReference model,
      Integer maxIterations,
      int maxFrameworkRetries,
      List<SkillRef> skills,
      Map<ToolKind, Integer> tools,
      Authorization authorization,
      Input input,
      List<PrivateTool> privateTools,
      List<MemoryMatchView> memoryMatches,
      List<McpToolDecision> mcpToolDecisions) {
    public Components {
      Objects.requireNonNull(definition);
      Objects.requireNonNull(model);
      Objects.requireNonNull(authorization);
      Objects.requireNonNull(input);
      if (!validDigest(definitionSha256)
          || (maxIterations != null && maxIterations < 1)
          || maxFrameworkRetries < 0) throw new IllegalArgumentException("装配定义摘要或执行器配置无效。");
      skills = List.copyOf(skills);
      tools = Map.copyOf(tools);
      privateTools = privateTools == null ? List.of() : List.copyOf(privateTools);
      memoryMatches = memoryMatches == null ? List.of() : List.copyOf(memoryMatches);
      // 历史快照没有逐工具准入明细，缺失字段必须归一为空列表而不是 null。
      mcpToolDecisions = mcpToolDecisions == null ? List.of() : List.copyOf(mcpToolDecisions);
    }

    /** 兼容没有逐 MCP 工具准入明细的装配（含历史快照反序列化）。 */
    public Components(
        AgentDefinitionRef definition,
        String definitionSha256,
        ModelReference model,
        Integer maxIterations,
        int maxFrameworkRetries,
        List<SkillRef> skills,
        Map<ToolKind, Integer> tools,
        Authorization authorization,
        Input input,
        List<PrivateTool> privateTools,
        List<MemoryMatchView> memoryMatches) {
      this(
          definition,
          definitionSha256,
          model,
          maxIterations,
          maxFrameworkRetries,
          skills,
          tools,
          authorization,
          input,
          privateTools,
          memoryMatches,
          List.of());
    }

    /** 兼容没有记忆召回的装配。 */
    public Components(
        AgentDefinitionRef definition,
        String definitionSha256,
        ModelReference model,
        Integer maxIterations,
        int maxFrameworkRetries,
        List<SkillRef> skills,
        Map<ToolKind, Integer> tools,
        Authorization authorization,
        Input input,
        List<PrivateTool> privateTools) {
      this(
          definition,
          definitionSha256,
          model,
          maxIterations,
          maxFrameworkRetries,
          skills,
          tools,
          authorization,
          input,
          privateTools,
          List.of(),
          List.of());
    }

    /** 兼容没有私有工具记录的历史装配。 */
    public Components(
        AgentDefinitionRef definition,
        String definitionSha256,
        ModelReference model,
        Integer maxIterations,
        int maxFrameworkRetries,
        List<SkillRef> skills,
        Map<ToolKind, Integer> tools,
        Authorization authorization,
        Input input) {
      this(
          definition,
          definitionSha256,
          model,
          maxIterations,
          maxFrameworkRetries,
          skills,
          tools,
          authorization,
          input,
          List.of(),
          List.of(),
          List.of());
    }
  }

  /** 一条被注入记忆的得分与召回理由；不保存正文。 */
  public record MemoryMatchView(
      String memoryId,
      String memoryType,
      double score,
      String matchedReason,
      int version) {
    public MemoryMatchView {
      memoryId = memoryId == null ? "" : memoryId;
      memoryType = memoryType == null ? "SEMANTIC" : memoryType;
      score = Math.max(0, Math.min(1, score));
      matchedReason = matchedReason == null ? "" : matchedReason;
    }
  }

  /** 文件路径仅参与范围摘要，工作区标识用于查找现有授权对象。 */
  public record Authorization(
      int policyVersion,
      AgentRole profile,
      int profileVersion,
      String workspaceId,
      int fileCount,
      String fileScopeSha256,
      boolean writeAuthorized) {
    public Authorization {
      Objects.requireNonNull(profile);
      if (policyVersion < 1
          || profileVersion < 1
          || fileCount < 0
          || (workspaceId == null
              ? fileCount != 0 || fileScopeSha256 != null || writeAuthorized
              : fileCount < 1 || !validDigest(fileScopeSha256)))
        throw new IllegalArgumentException("工具授权快照无效。");
    }
  }

  public record Input(String sha256, List<AgentInput.Source> sources) {
    public Input {
      if (!validDigest(sha256)) throw new IllegalArgumentException("上下文摘要无效。");
      sources = List.copyOf(sources);
    }
  }

  public record Recorded(UUID agentId, Instant recordedAt, AgentAssemblySnapshot snapshot) {}

  public static String sha256(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("无法计算装配摘要。", e);
    }
  }

  private static boolean validDigest(String value) {
    return value != null && value.matches("[0-9a-f]{64}");
  }
}
