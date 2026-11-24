package dev.workbench.ownership;

import dev.workbench.auth.AuthService;
import dev.workbench.domain.ExecutionScope;
import dev.workbench.domain.ExecutionScopeResolver;
import dev.workbench.persistence.entity.ResourceOwnershipEntity;
import dev.workbench.persistence.repository.ResourceOwnershipRepository;
import dev.workbench.persistence.repository.RunRepository;
import dev.workbench.persistence.repository.TaskRepository;
import dev.workbench.persistence.repository.WorkspaceRepository;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 任务、工作区和运行的服务端归属校验。
 *
 * <p>运行与证据从任务继承归属；旧记录没有归属登记时只在管理员显式领取后可见。任何列表、按 ID 查询、 确认、报告和下载都必须经过这里，不能把请求体里的用户标识当作授权依据。
 */
@Service
public class OwnershipService implements dev.workbench.framework.ownership.OwnershipStamper {
  public static final String TASK = "TASK";
  public static final String WORKSPACE = "WORKSPACE";

  private final AuthService auth;
  private final ExecutionScopeResolver scopes;
  private final TaskRepository tasks;
  private final WorkspaceRepository workspaces;
  private final RunRepository runs;
  private final ResourceOwnershipRepository owners;

  public OwnershipService(
      AuthService auth,
      ExecutionScopeResolver scopes,
      TaskRepository tasks,
      WorkspaceRepository workspaces,
      RunRepository runs,
      ResourceOwnershipRepository owners) {
    this.auth = auth;
    this.scopes = scopes;
    this.tasks = tasks;
    this.workspaces = workspaces;
    this.runs = runs;
    this.owners = owners;
  }

  public ExecutionScope scope() {
    return scopes.current();
  }

  /** 初始化前保留单机访问；初始化后旧 LOCAL 记录只对已领取的管理员可见。 */
  public boolean canRead(String ownerScopeKey) {
    if (!auth.initialized()) return true;
    if (ownerScopeKey == null || "LOCAL".equals(ownerScopeKey)) return auth.canReadLegacy();
    return scopes.current().ownerScopeKey().equals(ownerScopeKey);
  }

  @Transactional
  public void stamp(String resourceType, String resourceId) {
    if (resourceType == null || resourceId == null) return;
    owners.save(
        new ResourceOwnershipEntity(
            resourceType,
            resourceId,
            scopes.current().ownerScopeKey(),
            java.time.Instant.now().toString()));
  }

  public Optional<String> owner(String resourceType, String resourceId) {
    return owners.findOwner(resourceType, resourceId);
  }

  public void requireTask(String taskId) {
    tasks.findById(taskId).orElseThrow(() -> new EmptyResultDataAccessException(1));
    if (!canRead(owner(TASK, taskId).orElse(null))) throw denied();
  }

  /** 列表过滤使用；任务不存在时不展示，不泄露其他归属的存在性。 */
  public boolean canReadTask(String taskId) {
    return tasks.findById(taskId).isPresent() && canRead(owner(TASK, taskId).orElse(null));
  }

  /**
   * 当前会话可读取的任务标识；旧记录按领取状态判定。
   *
   * <p>不再 {@code tasks.findAll()} 拉全表：先按归属集合精确取号，再单独取出「没有归属登记」的
   * 历史任务（仅单机模式或已领取历史时可见），任务列表随数据量增长保持线性。
   */
  @Transactional(readOnly = true)
  public List<String> readableTaskIds() {
    var ownersByTask = new java.util.HashMap<String, String>();
    for (var row : owners.findByResourceType(TASK))
      ownersByTask.put(row.getResourceId(), row.getOwnerScopeKey());
    var result = new java.util.LinkedHashSet<String>();
    boolean legacyVisible = !auth.initialized() || auth.canReadLegacy();
    if (legacyVisible) result.addAll(tasks.findIdsWithoutOwnership(TASK));
    var visibleOwned =
        ownersByTask.entrySet().stream()
            .filter(entry -> canRead(entry.getValue()))
            .map(java.util.Map.Entry::getKey)
            .toList();
    if (!visibleOwned.isEmpty()) result.addAll(tasks.findExistingIds(visibleOwned));
    return List.copyOf(result);
  }

  public void requireWorkspace(String workspaceId) {
    workspaces.findById(workspaceId).orElseThrow(() -> new EmptyResultDataAccessException(1));
    if (!canRead(owner(WORKSPACE, workspaceId).orElse(null))) throw denied();
  }

  public void requireRun(String runId) {
    String taskId =
        runs.findOwner(runId)
            .map(owner -> owner.taskId())
            .orElseThrow(() -> new EmptyResultDataAccessException(1));
    requireTask(taskId);
  }

  public String taskIdOfRun(String runId) {
    return runs.findOwner(runId)
        .map(owner -> owner.taskId())
        .orElseThrow(() -> new EmptyResultDataAccessException(1));
  }

  public Optional<String> workspaceOwner(String workspaceId) {
    return owner(WORKSPACE, workspaceId);
  }

  private static AuthService.AccessDeniedException denied() {
    return new AuthService.AccessDeniedException("无权访问该资源。");
  }
}
