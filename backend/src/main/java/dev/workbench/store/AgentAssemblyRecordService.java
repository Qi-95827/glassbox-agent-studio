package dev.workbench.store;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.workbench.domain.*;
import dev.workbench.framework.agent.assembly.AgentAssemblySnapshot;
import dev.workbench.framework.agent.definition.AgentDefinitionRef;
import dev.workbench.framework.skill.SkillAssignment;
import dev.workbench.framework.skill.SkillRef;
import dev.workbench.framework.tool.AgentToolGrant;
import dev.workbench.framework.workflow.plan.PlanOperations;
import dev.workbench.persistence.repository.AgentConfigurationRepository;
import dev.workbench.persistence.repository.HandoffRepository;
import dev.workbench.persistence.repository.InvocationRepository;
import dev.workbench.persistence.repository.view.AgentData;
import dev.workbench.persistence.repository.view.ModelData;
import dev.workbench.persistence.repository.view.ToolData;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Agent 实例、实际装配、定义/技能/工具授权和模型与工具调用记录。 */
@Service
public class AgentAssemblyRecordService {
  private final AgentConfigurationRepository agentConfiguration;
  private final HandoffRepository handoffRows;
  private final TransactionTemplate tx;
  private final ObjectMapper json;
  private final InvocationRepository invocationRows;
  private final PlanOperations plans;
  private final HandoffValidator handoffValidator;
  private final RunEvidenceRecordService evidence;
  private final TaskRecordService tasks;

  public AgentAssemblyRecordService(
      AgentConfigurationRepository agentConfiguration,
      HandoffRepository handoffRows,
      TransactionTemplate tx,
      ObjectMapper json,
      InvocationRepository invocationRows,
      PlanOperations plans,
      HandoffValidator handoffValidator,
      RunEvidenceRecordService evidence,
      TaskRecordService tasks) {
    this.agentConfiguration = agentConfiguration;
    this.handoffRows = handoffRows;
    this.tx = tx;
    this.json = json;
    this.invocationRows = invocationRows;
    this.plans = plans;
    this.handoffValidator = handoffValidator;
    this.evidence = evidence;
    this.tasks = tasks;
  }

  public List<Map<String, Object>> agents(String runId) {
    return invocationRows.findAgentCalls(runId).stream().map(AgentData::apiFields).toList();
  }

  /** 读取真实实例所认领节点的定义；历史节点没有记录时返回空。 */
  public PlannedDefinition plannedDefinitionForAgent(String runId, String agentId) {
    UUID instanceId = UUID.fromString(agentId);
    var matching =
        plans.find(runId).stream().filter(node -> instanceId.equals(node.agentId())).toList();
    if (matching.size() != 1) throw new IllegalStateException("Agent 与计划节点的归属不一致。");
    var node = matching.getFirst();
    if (node.definitionRef() != null && node.definitionSha256() == null) {
      throw new IllegalStateException("计划缺少 Agent 定义摘要，不能安全执行。");
    }
    return new PlannedDefinition(node.definitionRef(), node.definitionSha256());
  }

  /** 历史计划引用和摘要可同时为空；不根据当前定义伪造历史摘要。 */
  public record PlannedDefinition(AgentDefinitionRef reference, String sha256) {}

  /** 完整装配及兼容查询所需投影在同一事务内发布。 */
  public void recordAgentAssembly(String runId, String agentId, AgentAssemblySnapshot snapshot) {
    tx.executeWithoutResult(
        ignored -> {
          var selected = snapshot.components();
          var planned = plannedDefinitionForAgent(runId, agentId);
          if (planned.reference() != null
              && (!planned.reference().equals(selected.definition())
                  || !planned.sha256().equals(selected.definitionSha256())))
            throw new IllegalStateException("Agent 实际装配与计划指定的定义不一致。");
          if (tasks.agentRole(runId, agentId) != selected.authorization().profile())
            throw new IllegalStateException("Agent 装配权限与已认领实例不一致。");
          recordAgentDefinition(runId, agentId, selected.definition(), selected.definitionSha256());
          recordAgentSkills(agentId, selected.skills());
          recordAgentVersionedToolGrants(agentId, selected.tools());
          try {
            agentConfiguration.insertAssembly(
                agentId, RecordSupport.now(), json.writeValueAsString(snapshot));
          } catch (JsonProcessingException e) {
            throw new IllegalStateException("实际装配快照无法保存。", e);
          }
        });
  }

  /** 历史没有快照的实例不会由现行配置推测补齐。 */
  public List<AgentAssemblySnapshot.Recorded> agentAssemblies(String runId) {
    return agentConfiguration.findAssemblies(runId).stream()
        .map(
            row -> {
              try {
                return new AgentAssemblySnapshot.Recorded(
                    UUID.fromString(row.agentId()),
                    Instant.parse(row.recordedAt()),
                    json.readValue(row.payload(), AgentAssemblySnapshot.class));
              } catch (JsonProcessingException e) {
                throw new IllegalStateException("实际装配快照版本不受支持或内容无法读取。", e);
              }
            })
        .toList();
  }

  /** 仅记录运行中实例实际解析的定义；历史空值不自动推断。 */
  public void recordAgentDefinition(
      String runId, String agentId, AgentDefinitionRef ref, String sha256) {
    Objects.requireNonNull(ref, "Agent 定义引用不能为空");
    if (sha256 == null || !sha256.matches("[0-9a-f]{64}"))
      throw new IllegalArgumentException("Agent 定义摘要无效。");
    if (!invocationRows.recordDefinition(runId, agentId, ref.id(), ref.version(), sha256))
      throw new IllegalStateException("Agent 不存在、已结束或定义已记录。");
  }

  /** 记录已装配技能的不可变引用；不记录提示正文，也不授予工具权限。 */
  public void recordAgentSkills(String agentId, List<SkillRef> skills) {
    tx.executeWithoutResult(
        s -> {
          if (!invocationRows.isRunningAgent(agentId))
            throw new IllegalStateException("Agent 未处于可装配技能的状态。");
          for (SkillRef skill : skills) {
            if (skill == null
                || skill.id() == null
                || skill.version() < 1
                || skill.contentSha256() == null
                || !skill.contentSha256().matches("[0-9a-f]{64}"))
              throw new IllegalArgumentException("技能引用无效。");
            agentConfiguration.insertSkill(agentId, skill, RecordSupport.now());
          }
        });
  }

  public List<SkillAssignment> skills(String runId) {
    return agentConfiguration.findSkills(runId);
  }

  /** 注册集合和空集合标记在同一事务保存；失败时不应启动模型。 */
  public void recordAgentToolGrants(String agentId, Set<ToolKind> tools) {
    if (tools == null || tools.stream().anyMatch(Objects::isNull))
      throw new IllegalArgumentException("工具集合无效。");
    var versions = new EnumMap<ToolKind, Integer>(ToolKind.class);
    for (ToolKind tool : tools) versions.put(tool, null);
    saveAgentToolGrants(agentId, versions);
  }

  /** 新装配必须提供明确的服务端工具契约版本。 */
  public void recordAgentVersionedToolGrants(String agentId, Map<ToolKind, Integer> tools) {
    if (tools == null
        || tools.entrySet().stream()
            .anyMatch(e -> e.getKey() == null || e.getValue() == null || e.getValue() < 1))
      throw new IllegalArgumentException("工具版本无效。");
    saveAgentToolGrants(agentId, Map.copyOf(tools));
  }

  private void saveAgentToolGrants(String agentId, Map<ToolKind, Integer> tools) {
    tx.executeWithoutResult(
        status -> {
          if (!invocationRows.isRunningAgent(agentId))
            throw new IllegalStateException("Agent 未处于可装配工具的状态。");
          agentConfiguration.insertToolGrants(agentId, tools, RecordSupport.now());
        });
  }

  /** 缺失标记与已登记空集合分开。 */
  public List<AgentToolGrant> agentToolGrants(String runId) {
    return agentConfiguration.findToolGrants(runId);
  }

  public List<Map<String, Object>> tools(String runId) {
    return invocationRows.findToolCalls(runId).stream().map(ToolData::apiFields).toList();
  }

  public List<Map<String, Object>> models(String runId) {
    return invocationRows.findModelCalls(runId).stream().map(ModelData::apiFields).toList();
  }

  public void startModel(String id, String runId, String agentId, ModelKind model) {
    invocationRows.insertModel(id, runId, agentId, model, RecordSupport.now());
  }

  public void finishModel(
      String id,
      InvocationStatus status,
      Integer input,
      Integer output,
      String error,
      long duration) {
    invocationRows.finishModel(id, status, input, output, error, duration, RecordSupport.now());
  }

  public void startTool(String id, String runId, String agentId, String toolName) {
    startTool(id, runId, agentId, toolName, null);
  }

  public void startTool(String id, String runId, String agentId, String toolName, Integer version) {
    if (version != null && version < 1) throw new IllegalArgumentException("工具版本无效。");
    invocationRows.insertTool(id, runId, agentId, toolName, version, RecordSupport.now());
  }

  /** 已启用重复保护时，调用记录与指纹在同一事务中写入。 */
  public void startTool(
      String id, String runId, String agentId, ToolKind kind, String fingerprint) {
    startTool(id, runId, agentId, kind, null, fingerprint);
  }

  public void startTool(
      String id, String runId, String agentId, ToolKind kind, Integer version, String fingerprint) {
    if (fingerprint == null || !fingerprint.matches("[0-9a-f]{64}"))
      throw new IllegalArgumentException("工具调用指纹无效。");
    tx.executeWithoutResult(
        s -> {
          startTool(id, runId, agentId, kind.recordName(), version);
          invocationRows.insertToolFingerprint(runId, id, kind, fingerprint);
        });
  }

  public void finishTool(
      String id, InvocationStatus status, String result, String error, long duration) {
    finishTool(id, status, result, error, duration, null);
  }

  /** 只读工具的资源版本与调用终态一起保存；旧调用没有该证据时保持为空。 */
  public void finishTool(
      String id,
      InvocationStatus status,
      String result,
      String error,
      long duration,
      String resourceVersion) {
    invocationRows.finishTool(
        id, status, result, error, duration, resourceVersion, RecordSupport.now());
  }

  public void startAgent(String id, String runId, String goal, AgentRole role) {
    tx.executeWithoutResult(ignored -> startAgentRecord(id, runId, goal, role, RecordSupport.now()));
  }

  /** 首次委派、实例启动和接收记录共同提交，避免半成品运行。 */
  public void startAgentWithDelegation(String id, String runId, String goal, Handoff delegation) {
    tx.executeWithoutResult(
        ignored -> {
          if (delegation == null
              || delegation.handoffId() == null
              || !id.equals(delegation.agentId())
              || !id.equals(delegation.recipientAgentId())
              || !runId.equals(delegation.runId())
              || !goal.equals(delegation.goal())
              || delegation.source() != HandoffSource.COORDINATOR)
            throw new IllegalArgumentException("委派的接收实例、来源或运行不匹配。");
          String time = RecordSupport.now();
          startAgentRecord(id, runId, goal, AgentRole.ANALYST, time);
          evidence.handoff(HandoffDirection.DELEGATION, delegation);
          handoffRows.insertReceipt(delegation.handoffId().toString(), runId, id, time);
          evidence.event(
              runId, EventType.HANDOFF_ACCEPTED, "已记录被委派 Agent 启动；不证明模型已读取委派内容。");
        });
  }

  /** 启动下游实例和确认交接为同一事务；确认不证明模型已读取输入。 */
  public void startAgentFromHandoff(
      String id, String runId, String goal, AgentRole role, UUID handoffId) {
    tx.executeWithoutResult(
        ignored -> {
          var row =
              handoffRows
                  .findById(handoffId.toString())
                  .filter(item -> runId.equals(item.runId()))
                  .orElseThrow(() -> new EmptyResultDataAccessException(1));
          Handoff handoff;
          try {
            handoff = json.readValue(row.json(), Handoff.class);
          } catch (JsonProcessingException e) {
            throw new IllegalStateException("交接内容无法读取。", e);
          }
          if (!HandoffDirection.AGENT_TRANSFER.name().equals(row.direction())
              || !handoffId.equals(handoff.handoffId())
              || !id.equals(handoff.recipientAgentId())
              || !runId.equals(handoff.runId())
              || !goal.equals(handoff.goal())
              || (role == AgentRole.CODER && handoff.receiver() != HandoffParty.CODING_AGENT)
              || (role == AgentRole.REVIEWER && handoff.receiver() != HandoffParty.REVIEW_AGENT)
              || (role != AgentRole.CODER && role != AgentRole.REVIEWER))
            throw new IllegalArgumentException("接收实例、角色或交接与运行不匹配。");
          handoffValidator.validate(HandoffDirection.AGENT_TRANSFER, handoff);
          if (!invocationRows.isCompletedAgent(handoff.agentId()))
            throw new IllegalStateException("上游 Agent 尚未完成，不能接收交接。");
          // 交接字段有效并不代表发送者属于当前接收节点的直接依赖。
          plans.requireDirectDependencySender(runId, role, UUID.fromString(handoff.agentId()));
          String time = RecordSupport.now();
          startAgentRecord(id, runId, goal, role, time);
          handoffRows.insertReceipt(handoffId.toString(), runId, id, time);
          evidence.event(
              runId, EventType.HANDOFF_ACCEPTED, "已记录接收 Agent 启动；不证明模型已读取交接内容。");
        });
  }

  /** 由调用方提供事务，确保计划节点和 Agent 调用一起写入。 */
  private void startAgentRecord(String id, String runId, String goal, AgentRole role, String time) {
    plans.start(runId, id, role, time);
    insertAgent(id, runId, goal, role, time);
  }

  public void insertAgent(String id, String runId, String goal, AgentRole role, String time) {
    invocationRows.insertAgent(id, runId, role, goal, time);
  }

  public void finishAgent(String id, InvocationStatus status, String result) {
    tx.executeWithoutResult(
        transaction -> {
          String time = RecordSupport.now();
          plans.finish(id, status, time);
          if (!invocationRows.finishAgent(id, status, result, time))
            throw new IllegalStateException("Agent 调用不存在或已经结束。");
        });
  }

  /** 成功结果交接与 Agent 结束状态共同提交；运行终态由协调者决定。 */
  public void finishAgentWithResult(String id, String result, Handoff resultHandoff) {
    if (resultHandoff == null
        || !id.equals(resultHandoff.agentId())
        || resultHandoff.status() != HandoffStatus.COMPLETED
        || !Objects.equals(result, resultHandoff.summary()))
      throw new IllegalArgumentException("Agent 结果与交接内容不一致。");
    tx.executeWithoutResult(
        ignored -> {
          finishAgent(id, InvocationStatus.COMPLETED, result);
          evidence.handoff(HandoffDirection.RESULT, resultHandoff);
        });
  }
}
