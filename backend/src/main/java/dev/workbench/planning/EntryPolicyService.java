package dev.workbench.planning;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.workbench.persistence.entity.EntryPolicyEntity;
import dev.workbench.persistence.repository.EntryPolicyRepository;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import dev.workbench.domain.TaskEntryKind;

/**
 * 四类业务入口的受信输入策略、交付物与默认验收条件。
 *
 * <p>策略只收紧现有任务契约，不授予工具权限；实际权限仍由工作流模板和任务授权决定。
 */
@Service
public class EntryPolicyService {
  public static final int POLICY_VERSION = 1;
  private final EntryPolicyRepository store;
  private final ObjectMapper json;

  public EntryPolicyService(EntryPolicyRepository store, ObjectMapper json) {
    this.store = store;
    this.json = json;
  }

  public List<EntryPolicy> all() {
    return List.of(
        policy(
            TaskEntryKind.REQUIREMENT,
            List.of("任务目标 1–500 字", "约束可选", "验收条件可选；缺失时使用默认条件"),
            List.of("需求说明：目标、范围、约束和验收条件", "缺失信息与待确认事项"),
            List.of("用户确认目标、范围、约束和验收条件"),
            "不声称设计、实现或测试已经完成。"),
        policy(
            TaskEntryKind.DESIGN,
            List.of("任务目标 1–500 字", "如声明写入授权，必须选择工作区和文件"),
            List.of("概要设计：模块边界、依赖、数据/接口变化", "风险、失败路径和待确认事项"),
            List.of("设计覆盖入口、状态、失败路径和验收条件"),
            "设计产物需要用户确认，不能替代实际实现或测试。"),
        policy(
            TaskEntryKind.CODE,
            List.of("任务目标 1–500 字", "必须选择工作区", "必须显式授权写入", "至少授权一个现有文件"),
            List.of("授权范围内的实际代码差异", "受影响的验证或回归证据", "未验证范围和剩余风险"),
            List.of("代码差异可审查", "授权范围内相关验证通过；未执行项明确列出"),
            "不能扩大文件权限，不能用模型结论代替测试证据。"),
        policy(
            TaskEntryKind.TEST,
            List.of("任务目标 1–500 字", "必须选择工作区", "至少授权一个测试可见文件"),
            List.of("测试计划与覆盖范围", "实际执行结果及证据", "未执行、不可用或失败项"),
            List.of("明确区分已执行测试与未执行项", "失败项有原因和证据"),
            "没有执行的测试不能声称通过。"));
  }

  public EntryPolicy get(TaskEntryKind kind) {
    return all().stream()
        .filter(policy -> policy.kind().equals(kind.name()))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("该入口没有独立策略。"));
  }

  /** 在创建任务前校验；返回持久化所需的交付物、验收条件和提示。 */
  public Validation validate(
      TaskEntryKind kind,
      String goal,
      String workspaceId,
      boolean writeEnabled,
      List<String> fileScopes,
      List<String> acceptanceCriteria) {
    EntryPolicy policy = get(kind);
    List<String> warnings = new java.util.ArrayList<>();
    if (kind == TaskEntryKind.CODE) {
      if (workspaceId == null) throw new IllegalArgumentException("代码入口必须选择工作区。");
      if (!writeEnabled) throw new IllegalArgumentException("代码入口必须显式授权写入。");
      if (fileScopes == null || fileScopes.isEmpty())
        throw new IllegalArgumentException("代码入口至少授权一个文件。");
    }
    if (kind == TaskEntryKind.TEST) {
      if (workspaceId == null) throw new IllegalArgumentException("测试入口必须选择工作区。");
      if (fileScopes == null || fileScopes.isEmpty())
        throw new IllegalArgumentException("测试入口至少授权一个测试可见文件。");
      if (writeEnabled) warnings.add("测试入口允许写入时仍受任务文件授权和模板范围限制。");
    }
    if ((kind == TaskEntryKind.DESIGN || kind == TaskEntryKind.REQUIREMENT) && writeEnabled) {
      if (workspaceId == null || fileScopes == null || fileScopes.isEmpty())
        throw new IllegalArgumentException("声明写入的入口必须选择工作区并授权文件。");
    }
    List<String> acceptance =
        acceptanceCriteria == null || acceptanceCriteria.isEmpty()
            ? policy.defaultAcceptance()
            : List.copyOf(acceptanceCriteria);
    return new Validation(policy, List.copyOf(acceptance), List.copyOf(warnings));
  }

  public void save(String taskId, Validation validation) {
    try {
      store.save(
          new EntryPolicyEntity(
              taskId,
              validation.policy().version(),
              json.writeValueAsString(validation.policy().deliverables()),
              json.writeValueAsString(validation.acceptance()),
              json.writeValueAsString(validation.warnings()),
              Instant.now().toString()));
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("入口交付物无法保存。", e);
    }
  }

  public Map<String, Object> find(String taskId) {
    var row = store.findById(taskId).orElse(null);
    if (row == null) return null;
    Map<String, Object> saved = new LinkedHashMap<>();
    saved.put("POLICY_VERSION", row.getPolicyVersion());
    saved.put("DELIVERABLES_JSON", row.getDeliverablesJson());
    saved.put("ACCEPTANCE_JSON", row.getAcceptanceJson());
    saved.put("WARNINGS_JSON", row.getWarningsJson());
    saved.put("CREATED_AT", row.getCreatedAt());
    return saved;
  }

  private static EntryPolicy policy(
      TaskEntryKind kind,
      List<String> requiredInputs,
      List<String> deliverables,
      List<String> defaultAcceptance,
      String guidance) {
    return new EntryPolicy(
        kind.name(),
        kind.label(),
        POLICY_VERSION,
        requiredInputs,
        deliverables,
        defaultAcceptance,
        guidance);
  }

  public record EntryPolicy(
      String kind,
      String label,
      int version,
      List<String> requiredInputs,
      List<String> deliverables,
      List<String> defaultAcceptance,
      String guidance) {}

  public record Validation(
      EntryPolicy policy, List<String> acceptance, List<String> warnings) {}
}
