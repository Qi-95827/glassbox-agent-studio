package dev.workbench.observability;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.workbench.persistence.repository.MonitoringDashboardRepository;
import dev.workbench.persistence.repository.MonitoringDashboardRepository.RunRow;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/** 验证同一历史运行可按请求语言实时生成中文或英文排查向导。 */
class RunDiagnosticsServiceLocaleTest {

  private static RunDiagnostics diagnose(Locale locale) {
    var repository = mock(MonitoringDashboardRepository.class);
    when(repository.findRun("run-1"))
        .thenReturn(
            new RunRow(
                "run-1",
                "task-1",
                "Build a page",
                "COMPLETED",
                null,
                null,
                "2026-01-01T00:00:00Z",
                "2026-01-01T00:01:00Z",
                60_000,
                "mock",
                null));
    when(repository.nodes(List.of("run-1"))).thenReturn(List.of());
    when(repository.modelCalls(List.of("run-1"))).thenReturn(List.of());
    when(repository.toolCalls(List.of("run-1"))).thenReturn(List.of());
    when(repository.gatewayTraces(List.of("run-1"))).thenReturn(List.of());
    when(repository.modelPayloads("run-1")).thenReturn(List.of());
    when(repository.handoffs("run-1")).thenReturn(List.of());
    when(repository.recentEvents("run-1", 30)).thenReturn(List.of());
    when(repository.assemblies(List.of())).thenReturn(List.of());
    when(repository.gitActions(null)).thenReturn(List.of());
    when(repository.deliveredWorkspaceIds()).thenReturn(java.util.Set.of());
    return new RunDiagnosticsService(repository, new ObjectMapper()).diagnose("run-1", locale);
  }

  @Test
  void producesEnglishWizardForEnglishRequest() {
    var diagnostic = diagnose(Locale.US);
    assertEquals(RunDiagnostics.HEALTHY, diagnostic.healthLevel());
    assertTrue(diagnostic.primaryIssue().startsWith("No failures"));
    assertEquals(5, diagnostic.troubleshootingSteps().size());
    assertTrue(diagnostic.troubleshootingSteps().getFirst().title().startsWith("Step 1"));
    assertTrue(diagnostic.troubleshootingSteps().getFirst().actionLabel().contains("View"));
  }

  @Test
  void producesChineseWizardForChineseRequest() {
    var diagnostic = diagnose(Locale.SIMPLIFIED_CHINESE);
    assertTrue(diagnostic.primaryIssue().startsWith("未发现失败"));
    assertTrue(diagnostic.troubleshootingSteps().getFirst().title().startsWith("步骤 1"));
    assertTrue(diagnostic.troubleshootingSteps().getFirst().actionLabel().contains("查看"));
  }
}
