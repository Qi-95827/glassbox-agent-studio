package dev.workbench.planning;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.workbench.framework.planning.PlanCandidateModel;
import dev.workbench.persistence.entity.PlanCandidateEntity;
import dev.workbench.persistence.repository.PlanCandidateRepository;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 候选的查询、版本与过期判定、状态条件更新。
 *
 * <p>只封装候选自身的持久化语义：读取按执行归属过滤，写操作保留原有的条件更新与事务边界，晚到的模型结果不能覆盖取消或过期状态。
 */
class PlanCandidateStoreService {
  private final PlanCandidateRepository store;
  private final TransactionTemplate tx;
  private final ObjectMapper json;

  PlanCandidateStoreService(
      PlanCandidateRepository store, TransactionTemplate tx, ObjectMapper json) {
    this.store = store;
    this.tx = tx;
    this.json = json;
  }

  Optional<PlanCandidateEntity> findByRequestId(String ownerScopeKey, String requestId) {
    return store.findByOwnerScopeKeyAndRequestId(ownerScopeKey, requestId);
  }

  Optional<PlanCandidateEntity> findById(String id, String ownerScopeKey) {
    return store.findByIdAndOwnerScopeKey(id, ownerScopeKey);
  }

  Optional<PlanCandidateEntity> findById(String id) {
    return store.findById(id);
  }

  PlanCandidateEntity requireById(String id, String ownerScopeKey) {
    return findById(id, ownerScopeKey).orElseThrow(() -> new EmptyResultDataAccessException(1));
  }

  PlanCandidateEntity reload(PlanCandidateEntity row) {
    return requireById(row.getId(), row.getOwnerScopeKey());
  }

  List<PlanCandidateEntity> list(String ownerScopeKey, String groupId) {
    return store.findByOwnerScopeKeyAndGroupIdOrderByInputVersionDesc(ownerScopeKey, groupId);
  }

  PlanCandidateEntity save(PlanCandidateEntity row) {
    return store.saveAndFlush(row);
  }

  int latestInputVersion(String groupId) {
    return store.latestInputVersion(groupId);
  }

  /** 同组新输入提交后，仍可确认的旧候选立即过期；失败与取消保留原状态。 */
  void markGroupStale(String groupId, String exceptId) {
    store.markGroupStale(
        groupId, PlanCandidateStatus.READY.name(), PlanCandidateStatus.STALE.name(), exceptId);
  }

  boolean hasNewerInputVersion(PlanCandidateEntity row) {
    return store.existsByGroupIdAndInputVersionGreaterThan(
        row.getGroupId(), row.getInputVersion());
  }

  Optional<PlanCandidateEntity> lockById(String id, String ownerScopeKey) {
    return store.lockByIdAndOwner(id, ownerScopeKey);
  }

  int consume(String id, String runId) {
    return store.consume(
        id,
        PlanCandidateStatus.CONSUMED.name(),
        PlanCandidateStatus.READY.name(),
        runId,
        Instant.now().toString());
  }

  boolean beginPreparation(
      PlanCandidateEntity row, String workspaceOperationId, String preparationId) {
    Boolean started =
        tx.execute(
            status ->
                store.beginPreparation(
                        row.getId(),
                        row.getOwnerScopeKey(),
                        PlanCandidateStatus.READY.name(),
                        "PREPARING",
                        workspaceOperationId,
                        preparationId,
                        Instant.now().toString())
                    == 1);
    return Boolean.TRUE.equals(started);
  }

  int reassignPreparation(String id, String expectedId, String nextId) {
    Integer changed =
        tx.execute(
            status ->
                store.reassignPreparation(
                    id, expectedId, nextId, Instant.now().toString(), "PREPARING"));
    return changed == null ? 0 : changed;
  }

  int finishPreparation(String id, String preparationId, String workspaceId, String status) {
    Integer changed =
        tx.execute(
            ignored ->
                store.finishPreparation(
                    id, preparationId, workspaceId, status, "PREPARING"));
    return changed == null ? 0 : changed;
  }

  int cancelGeneration(String id) {
    Integer changed =
        tx.execute(
            status ->
                store.cancel(
                    id,
                    PlanCandidateStatus.CANCELLED.name(),
                    Instant.now().toString(),
                    PlanCandidateStatus.GENERATING.name()));
    return changed == null ? 0 : changed;
  }

  void interruptOrphans() {
    tx.executeWithoutResult(
        status ->
            store.interruptGenerating(
                PlanCandidateStatus.INTERRUPTED.name(),
                Instant.now().toString(),
                PlanCandidateStatus.GENERATING.name()));
  }

  /** 条件结算；同组已有更新输入版本时，晚到的 READY 结果降级为 STALE。 */
  void settle(
      String id,
      PlanCandidateStatus status,
      String sha256,
      String candidateJson,
      String agentBindingsJson,
      List<String> errors,
      PlanCandidateModel.Reply reply) {
    String errorsJson = errors.isEmpty() ? null : write(errors);
    tx.executeWithoutResult(
        ignored -> {
          var row = store.findById(id).orElse(null);
          if (row == null) return;
          PlanCandidateStatus effective =
              status == PlanCandidateStatus.READY && hasNewerInputVersion(row)
                  ? PlanCandidateStatus.STALE
                  : status;
          store.settle(
              id,
              effective.name(),
              sha256,
              candidateJson,
              agentBindingsJson,
              errorsJson,
              reply == null ? null : reply.modelName(),
              reply == null ? null : reply.inputTokens(),
              reply == null ? null : reply.outputTokens(),
              Instant.now().toString(),
              PlanCandidateStatus.GENERATING.name());
        });
  }

  PlanCandidateService.CandidateView view(PlanCandidateEntity row) {
    PlanCandidateInput input = read(row.getInputJson(), PlanCandidateInput.class);
    PlanCandidateDraft candidate =
        row.getCandidateJson() == null
            ? null
            : read(row.getCandidateJson(), PlanCandidateDraft.class);
    List<String> errors = row.getErrorsJson() == null ? List.of() : readList(row.getErrorsJson());
    Map<String, Object> bindings = Map.of();
    if (row.getAgentBindingsJson() != null) {
      try {
        bindings =
            json.readValue(
                row.getAgentBindingsJson(),
                json.getTypeFactory()
                    .constructMapType(LinkedHashMap.class, String.class, Object.class));
      } catch (JsonProcessingException e) {
        throw new IllegalStateException("候选 Agent 绑定记录无法读取。", e);
      }
    }
    return new PlanCandidateService.CandidateView(
        row.getId(),
        row.getStatus(),
        row.getSchemaVersion(),
        row.getInputVersion(),
        row.getInputSha256(),
        row.getCandidateSha256(),
        input,
        candidate,
        bindings,
        errors,
        row.getModelName(),
        row.getInputTokens(),
        row.getOutputTokens(),
        row.getCreatedAt(),
        row.getSettledAt(),
        row.getSourceCandidateId(),
        row.getGroupId(),
        row.getRunId());
  }

  String write(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("候选计划无法编码。", e);
    }
  }

  <T> T read(String value, Class<T> type) {
    try {
      return json.readValue(value, type);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("已保存的候选计划无法读取。", e);
    }
  }

  List<String> readList(String value) {
    try {
      return json.readValue(
          value, json.getTypeFactory().constructCollectionType(List.class, String.class));
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("已保存的候选原因无法读取。", e);
    }
  }

  PlanningAgentSnapshot readPlanning(String value) {
    if (value == null || value.isBlank()) return null;
    try {
      return json.readValue(value, PlanningAgentSnapshot.class);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("候选的 Agent 短引用映射无法读取。", e);
    }
  }
}
