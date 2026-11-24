package dev.workbench.ownership;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import dev.workbench.auth.AuthService;
import dev.workbench.domain.ExecutionScope;
import dev.workbench.domain.ExecutionScopeResolver;
import dev.workbench.persistence.entity.ResourceOwnershipEntity;
import dev.workbench.persistence.repository.ResourceOwnershipRepository;
import dev.workbench.persistence.repository.RunRepository;
import dev.workbench.persistence.repository.TaskRepository;
import dev.workbench.persistence.repository.WorkspaceRepository;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 任务列表可见性过滤不再全表扫描。
 *
 * <p>反例对应 `P-04`：此前每次都 {@code tasks.findAll()} 拉全表再逐条比对归属，任务一多就是平方级开销。
 */
class OwnershipReadableTasksTest {
  private final AuthService auth = mock(AuthService.class);
  private final ExecutionScopeResolver scopes = mock(ExecutionScopeResolver.class);
  private final TaskRepository tasks = mock(TaskRepository.class);
  private final WorkspaceRepository workspaces = mock(WorkspaceRepository.class);
  private final RunRepository runs = mock(RunRepository.class);
  private final ResourceOwnershipRepository owners = mock(ResourceOwnershipRepository.class);

  private OwnershipService service() {
    return new OwnershipService(auth, scopes, tasks, workspaces, runs, owners);
  }

  private static ResourceOwnershipEntity owner(String taskId, String scopeKey) {
    return new ResourceOwnershipEntity(
        OwnershipService.TASK, taskId, scopeKey, "2026-01-01T00:00:00Z");
  }

  @Test
  void returnsOwnedTasksPlusLegacyHistoryWithoutFullScan() {
    when(auth.initialized()).thenReturn(true);
    when(auth.canReadLegacy()).thenReturn(true);
    when(scopes.current()).thenReturn(new ExecutionScope("USER_A", "USER_A"));
    when(owners.findByResourceType(OwnershipService.TASK))
        .thenReturn(List.of(owner("t-a", "USER_A"), owner("t-b", "USER_B")));
    when(tasks.findIdsWithoutOwnership(OwnershipService.TASK)).thenReturn(List.of("t-legacy"));
    when(tasks.findExistingIds(any())).thenReturn(List.of("t-a"));

    var readable = service().readableTaskIds();

    assertEquals(List.of("t-legacy", "t-a"), readable);
    verify(tasks, never()).findAll();
    verify(tasks).findIdsWithoutOwnership(OwnershipService.TASK);
    verify(tasks).findExistingIds(List.of("t-a"));
  }

  @Test
  void hidesLegacyHistoryWhenNotClaimed() {
    when(auth.initialized()).thenReturn(true);
    when(auth.canReadLegacy()).thenReturn(false);
    when(scopes.current()).thenReturn(new ExecutionScope("USER_B", "USER_B"));
    when(owners.findByResourceType(OwnershipService.TASK))
        .thenReturn(List.of(owner("t-b", "USER_B")));
    when(tasks.findExistingIds(any())).thenReturn(List.of("t-b"));

    var readable = service().readableTaskIds();

    assertEquals(List.of("t-b"), readable);
    verify(tasks, never()).findIdsWithoutOwnership(any());
    verify(tasks, never()).findAll();
  }

  @Test
  void singleMachineModeSeesLegacyTasksWithoutOwnership() {
    when(auth.initialized()).thenReturn(false);
    when(owners.findByResourceType(OwnershipService.TASK)).thenReturn(List.of());
    when(tasks.findIdsWithoutOwnership(OwnershipService.TASK)).thenReturn(List.of("t-1", "t-2"));

    var readable = service().readableTaskIds();

    assertEquals(List.of("t-1", "t-2"), readable);
    verify(tasks, never()).findAll();
    verify(tasks, never()).findExistingIds(any());
  }
}
