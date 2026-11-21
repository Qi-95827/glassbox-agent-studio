package dev.workbench.framework.agent.definition;

import dev.workbench.domain.ModelMode;
import java.util.List;
import java.util.Optional;

/**
 * 私有 Agent 定义的只读端口。
 *
 * <p>framework 只消费固定版本的声明与组件快照；存储、归属校验和发布状态由具体模块实现。
 */
public interface PrivateAgentDefinitionSource {
  boolean isPrivateReference(AgentDefinitionRef ref);

  AgentDefinition definition(AgentDefinitionRef ref);

  String fingerprint(AgentDefinitionRef ref);

  Components components(AgentDefinitionRef ref);

  Optional<ModelMode> fixedModelMode(AgentDefinitionRef ref);

  /** 固定版本的 Skill 与 Tool 声明；只用于装配计划，不表示已授权。 */
  record Components(List<PinnedSkill> skills, List<PinnedTool> tools) {
    public Components {
      skills = skills == null ? List.of() : List.copyOf(skills);
      tools = tools == null ? List.of() : List.copyOf(tools);
    }

    public static Components empty() {
      return new Components(List.of(), List.of());
    }
  }

  record PinnedSkill(
      String id,
      int version,
      String name,
      String sha256,
      String guidance,
      String usageNotes,
      String inputContract,
      String outputContract) {}

  record PinnedTool(
      String id,
      int version,
      String name,
      String executorKind,
      String toolName,
      List<String> fileExtensions,
      String sha256) {
    public PinnedTool {
      fileExtensions = fileExtensions == null ? List.of() : List.copyOf(fileExtensions);
    }
  }
}
