package dev.workbench.planning;

import dev.workbench.persistence.entity.PlanCandidateEntity;
import dev.workbench.workspace.WorkspaceAuthorizationService;
import dev.workbench.workspace.WorkspaceService;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.UUID;

/**
 * 候选的工作区部分：规划时的文件版本快照、确认前的工作区基线复核，以及独立交付的准备工作区租约。
 *
 * <p>准备状态机保持原语义：只有未准备过的 READY 候选可以开始准备；PREPARING 在租约内视为活跃，其他确认等待而不是接管；超过租约后允许更换租约持有者，接管、结算与 UNKNOWN 都绑定预期的 preparationId。
 */
class PlanCandidateWorkspaceService {
  private static final Duration PREPARATION_LEASE = Duration.ofSeconds(60);
  private static final Duration PREPARATION_WAIT = Duration.ofSeconds(20);

  private final PlanCandidateStoreService store;
  private final WorkspaceService workspaces;
  private final WorkspaceAuthorizationService workspaceAuthorization;

  PlanCandidateWorkspaceService(
      PlanCandidateStoreService store,
      WorkspaceService workspaces,
      WorkspaceAuthorizationService workspaceAuthorization) {
    this.store = store;
    this.workspaces = workspaces;
    this.workspaceAuthorization = workspaceAuthorization;
  }

  /**
   * 规划时的已有工作区版本：基线提交 + 每个授权文件的 SHA-256。
   *
   * <p>确认运行时必须逐项复核；工作区或文件变化时旧候选不能物化，避免把规划时的路径和内容静默套用到新的工作区状态。
   */
  WorkspaceSnapshot snapshot(PlanCandidateInput input) {
    var workspace = workspaces.get(input.workspaceId());
    var versions = new java.util.TreeMap<String, String>();
    for (String path : input.fileScopes())
      versions.put(path, workspaces.read(input.workspaceId(), path).sha256());
    var digest = new CanonicalDigest("plan-workspace-v1").field(workspace.baseCommit());
    digest.field(versions.size());
    for (var entry : versions.entrySet()) digest.field(entry.getKey()).field(entry.getValue());
    return new WorkspaceSnapshot(digest.hex(), store.write(versions));
  }

  /** 已有工作区在规划后发生基线或文件变化时拒绝物化。 */
  void requireCurrent(PlanCandidateEntity row, PlanCandidateInput input) {
    if (input.workspaceId() == null) return;
    var current = snapshot(input);
    if (!current.fingerprint().equals(row.getWorkspaceFingerprint()))
      throw new IllegalStateException("工作区或授权文件在规划后已变化，请重新生成计划。");
  }

  /** 按当前授权重新计算有效文件范围；与候选冻结范围不一致时拒绝确认。 */
  WorkspaceAuthorizationService.Prepared prepareScope(
      String workspaceId, PlanCandidateInput input) {
    // 目录授权（含整个工作区 "."）已在生成时冻结有效文件，这里按目录与排除规则复验，而不是把
    // 展开结果重新当作手工选择的显式文件。
    if (!input.directoryScopes().isEmpty())
      return workspaceAuthorization.reverifyDirectories(
          workspaceId, input.directoryScopes(), input.excludedPaths(), input.fileScopes());
    var prepared =
        workspaceAuthorization.prepare(workspaceId, input.fileScopes(), java.util.List.of(), java.util.List.of());
    if (!new HashSet<>(prepared.effectiveFiles()).equals(new HashSet<>(input.fileScopes())))
      throw new IllegalStateException("候选文件范围与当前工作区授权不一致，请重新生成计划。");
    return prepared;
  }

  /**
   * 生成候选前的目录授权展开：整个工作区 {@code "."} 或子目录都会展开成有效文件集合。
   *
   * <p>只有服务端可以执行这一步，展开结果随输入快照冻结；没有目录授权时保持原有显式文件语义。
   */
  PlanCandidateInput expandDirectories(PlanCandidateInput input) {
    if (input.workspaceId() == null || input.directoryScopes().isEmpty()) return input;
    var prepared =
        workspaceAuthorization.prepare(
            input.workspaceId(), input.fileScopes(), input.directoryScopes(), input.excludedPaths());
    return input.withExpandedScope(
        prepared.directories(), prepared.exclusions(), prepared.effectiveFiles());
  }

  /**
   * 独立交付在此准备空白工作区；并发确认只有一个请求能推进准备状态。
   *
   * <p>仓库目录使用同一候选稳定的 workspaceOperationId；PREPARING 在租约内视为活跃，其他确认等待而不是接管。超过租约后租约持有者可以更换，但接管、结算和标记 UNKNOWN 都绑定预期的 preparationId，旧请求恢复后不能覆盖新状态，也不能再创建第二个运行。
   */
  String prepare(PlanCandidateEntity row, PlanCandidateInput input) {
    if (input.workspaceId() != null) return input.workspaceId();
    if (row.getPreparedWorkspaceId() != null) return row.getPreparedWorkspaceId();
    if ("UNKNOWN".equals(row.getPreparationStatus()))
      throw new IllegalStateException("独立工作区准备结果未知，请人工核对后再创建运行。");
    if ("PREPARING".equals(row.getPreparationStatus())) return coordinate(row, input);
    String workspaceOperationId =
        row.getWorkspaceOperationId() == null
            ? UUID.randomUUID().toString()
            : row.getWorkspaceOperationId();
    String preparationId = UUID.randomUUID().toString();
    boolean started = store.beginPreparation(row, workspaceOperationId, preparationId);
    if (!started) {
      var latest = store.reload(row);
      if (latest.getPreparedWorkspaceId() != null) return latest.getPreparedWorkspaceId();
      if ("PREPARING".equals(latest.getPreparationStatus())) return coordinate(latest, input);
      if ("UNKNOWN".equals(latest.getPreparationStatus()))
        throw new IllegalStateException("独立工作区准备结果未知，请人工核对后再创建运行。");
      throw new IllegalStateException("候选状态已变化，请刷新后重试。");
    }
    return create(row, input, workspaceOperationId, preparationId);
  }

  /** 等待活跃准备结束；只有超过租约的中断遗留才允许更换租约持有者。 */
  private String coordinate(PlanCandidateEntity row, PlanCandidateInput input) {
    PlanCandidateEntity latest = row;
    long deadline = System.nanoTime() + PREPARATION_WAIT.toNanos();
    while (true) {
      if (latest.getPreparedWorkspaceId() != null) return latest.getPreparedWorkspaceId();
      if (!"PREPARING".equals(latest.getPreparationStatus())) return prepare(latest, input);
      if (!active(latest.getPreparationStartedAt())) break;
      if (System.nanoTime() > deadline)
        throw new IllegalStateException("独立工作区仍在准备，请稍后重试确认。");
      dev.workbench.framework.concurrent.BoundedWait.pause(100);
      latest = store.reload(row);
    }
    String expected = latest.getPreparationId();
    String workspaceOperationId = latest.getWorkspaceOperationId();
    var target = latest;
    if (expected == null || workspaceOperationId == null) {
      markUnknown(target.getId(), expected);
      throw new IllegalStateException("独立工作区缺少准备标识，请重新生成计划。");
    }
    String next = UUID.randomUUID().toString();
    if (store.reassignPreparation(target.getId(), expected, next) != 1)
      return coordinate(store.reload(row), input);
    return adoptOrCreate(target, workspaceOperationId, next, input);
  }

  /** 接管后先按稳定的工作区操作标识核对已登记的目录，再决定复用还是进入人工核对。 */
  private String adoptOrCreate(
      PlanCandidateEntity row,
      String workspaceOperationId,
      String preparationId,
      PlanCandidateInput input) {
    var adopted = workspaces.findIdeaWorkspace(workspaceOperationId);
    if (adopted.isPresent()) {
      finish(row.getId(), preparationId, adopted.get().id(), "READY");
      return adopted.get().id();
    }
    if (workspaces.ideaRepositoryExists(workspaceOperationId)) {
      markUnknown(row.getId(), preparationId);
      throw new IllegalStateException("独立工作区目录已存在但未登记，请人工核对后再创建运行。");
    }
    return create(row, input, workspaceOperationId, preparationId);
  }

  private String create(
      PlanCandidateEntity row,
      PlanCandidateInput input,
      String workspaceOperationId,
      String preparationId) {
    WorkspaceService.Workspace workspace;
    try {
      workspace = workspaces.createIdeaWorkspace(input.fileScopes(), workspaceOperationId);
    } catch (RuntimeException e) {
      // 目录可能已被本次或上一次请求创建；只有已登记的工作区才能复用，否则转人工核对。
      var existing = workspaces.findIdeaWorkspace(workspaceOperationId);
      if (existing.isPresent()) {
        finish(row.getId(), preparationId, existing.get().id(), "READY");
        return existing.get().id();
      }
      markUnknown(row.getId(), preparationId);
      throw new IllegalStateException("独立工作区准备失败或目录已存在但未登记，请核对后再决定是否重新生成计划。");
    }
    // 结算绑定本次租约持有者；被接管时这里失败，旧请求不得继续创建运行。
    finish(row.getId(), preparationId, workspace.id(), "READY");
    return workspace.id();
  }

  private void finish(String id, String preparationId, String workspaceId, String status) {
    if (store.finishPreparation(id, preparationId, workspaceId, status) != 1)
      throw new IllegalStateException("工作区准备已被其他确认接管，请刷新后重试。");
  }

  private void markUnknown(String id, String preparationId) {
    if (preparationId == null) return;
    try {
      store.finishPreparation(id, preparationId, null, "UNKNOWN");
    } catch (RuntimeException ignored) {
      // 已被接管时保留接管者状态，不覆盖。
    }
  }

  private static boolean active(String startedAt) {
    if (startedAt == null) return false;
    try {
      return Instant.parse(startedAt).isAfter(Instant.now().minus(PREPARATION_LEASE));
    } catch (DateTimeParseException e) {
      return false;
    }
  }

  /** 规划时的已有工作区版本快照；空快照表示独立交付，没有可复核的既有基线。 */
  record WorkspaceSnapshot(String fingerprint, String fileVersionsJson) {
    static final WorkspaceSnapshot EMPTY = new WorkspaceSnapshot(null, null);
  }
}
