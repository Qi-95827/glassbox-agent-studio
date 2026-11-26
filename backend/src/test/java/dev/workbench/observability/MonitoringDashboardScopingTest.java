package dev.workbench.observability;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.workbench.ownership.OwnershipService;
import dev.workbench.persistence.repository.MonitoringDashboardRepository;
import dev.workbench.persistence.repository.MonitoringDashboardRepository.TaskOptionRow;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.EmptyResultDataAccessException;

/**
 * 单任务监控的归属边界。
 *
 * <p>只验证不依赖数据库的判定：传入他人任务或未知任务一律 404、不回退全局；任务与运行不匹配同样拒绝； 命中可读任务时返回聚焦横幅所需的 selectedTaskId / selectedTaskGoal 与筛选选项。
 */
class MonitoringDashboardScopingTest {
  private final MonitoringDashboardRepository repository =
      mock(MonitoringDashboardRepository.class);
  private final OwnershipService ownership = mock(OwnershipService.class);
  private MonitoringDashboardService service;

  @BeforeEach
  void setUp() {
    service = new MonitoringDashboardService(repository, ownership, new ObjectMapper());
    when(ownership.readableTaskIds()).thenReturn(List.of("task-a"));
    when(repository.taskOptions(any(), anyInt()))
        .thenReturn(
            List.of(
                new TaskOptionRow("task-a", "实现单文件番茄钟", "COMPLETED", "2026-01-01T00:00:00Z", 3)));
    when(repository.runs(any(), any())).thenReturn(List.of());
  }

  @Test
  void foreignOrUnknownTaskIdIsNotFoundInsteadOfFallingBackToGlobalData() {
    assertThrows(
        EmptyResultDataAccessException.class,
        () -> service.dashboard("7d", null, null, "task-b", null));
    assertThrows(
        EmptyResultDataAccessException.class,
        () -> service.dashboard("7d", null, null, "task-missing", null));
    // 全局视图不受影响：仍按可读任务集合计算。
    var global = service.dashboard("7d", null, null);
    assertNull(global.selectedTaskId());
    assertEquals(1, global.taskOptions().size());
  }

  @Test
  void runIdMustBelongToTheCurrentAccountAndToTheFilteredTask() {
    org.mockito.Mockito.doThrow(new EmptyResultDataAccessException(1))
        .when(ownership)
        .requireRun("run-foreign");
    assertThrows(
        EmptyResultDataAccessException.class,
        () -> service.dashboard("7d", null, null, null, "run-foreign"));

    when(ownership.taskIdOfRun("run-b")).thenReturn("task-b");
    assertThrows(
        EmptyResultDataAccessException.class,
        () -> service.dashboard("7d", null, null, "task-a", "run-b"));
  }

  @Test
  void scopedTaskReturnsFocusBannerFieldsAndKeepsTaskOptions() {
    var scoped = service.dashboard("all", null, null, "task-a", null);

    assertEquals("task-a", scoped.selectedTaskId());
    assertEquals("实现单文件番茄钟", scoped.selectedTaskGoal());
    assertEquals(1, scoped.taskOptions().size());
    assertEquals(3, scoped.taskOptions().getFirst().runCount());
    assertEquals(0, scoped.summary().totalRuns());
    assertTrue(scoped.rankings().recentRuns().isEmpty());
  }
}
