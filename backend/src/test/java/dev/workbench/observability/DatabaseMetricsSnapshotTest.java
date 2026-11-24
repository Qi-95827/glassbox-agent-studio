package dev.workbench.observability;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.workbench.persistence.repository.ObservabilityRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * 指标快照缓存：同一窗口内的所有 Gauge 共享一次计数，窗口过期或显式刷新才重新查库。
 *
 * <p>反例对应 `P-02`：此前每个 Gauge 每次抓取各执行一条 COUNT，一次 Prometheus 抓取要打二十多次库。 缓存语义直接用快照读取验证，避免 Micrometer
 * Gauge 的注册细节干扰计数断言；另外单独确认 Gauge 确实 注册成功。
 */
class DatabaseMetricsSnapshotTest {
  private DatabaseMetrics metrics;

  @Test
  void gaugesShareOneSnapshotWithinWindow() {
    var rows = mock(ObservabilityRepository.class);
    when(rows.countRunsByStatus("COMPLETED")).thenReturn(7L);
    metrics = new DatabaseMetrics(rows, Duration.ofMinutes(15));

    assertEquals(7.0, metrics.snapshotForRead().runs().get("COMPLETED"));
    assertEquals(7.0, metrics.snapshotForRead().runs().get("COMPLETED"));
    verify(rows, times(1)).countRunsByStatus("COMPLETED");
  }

  @Test
  void explicitRefreshPicksUpNewCounts() {
    var rows = mock(ObservabilityRepository.class);
    when(rows.countRunsByStatus("COMPLETED")).thenReturn(1L, 2L);
    metrics = new DatabaseMetrics(rows, Duration.ofMinutes(15));
    assertEquals(1.0, metrics.snapshotForRead().runs().get("COMPLETED"));

    metrics.refreshNow();

    assertEquals(2.0, metrics.snapshotForRead().runs().get("COMPLETED"));
    verify(rows, times(2)).countRunsByStatus("COMPLETED");
  }

  @Test
  void zeroWindowKeepsEveryReadFreshForTests() {
    var rows = mock(ObservabilityRepository.class);
    when(rows.countRunsByStatus("COMPLETED")).thenReturn(1L, 2L, 3L);
    metrics = new DatabaseMetrics(rows, Duration.ZERO);

    assertEquals(1.0, metrics.snapshotForRead().runs().get("COMPLETED"));
    assertEquals(2.0, metrics.snapshotForRead().runs().get("COMPLETED"));
    assertEquals(3.0, metrics.snapshotForRead().runs().get("COMPLETED"));
    verify(rows, times(3)).countRunsByStatus("COMPLETED");
  }

  @Test
  void missingNumbersAreReportedAsZero() {
    var rows = mock(ObservabilityRepository.class);
    metrics = new DatabaseMetrics(rows, Duration.ofMinutes(15));

    var snapshot = metrics.snapshotForRead();
    assertEquals(0.0, snapshot.runs().getOrDefault("COMPLETED", 0.0));
    assertEquals(0.0, snapshot.models().getOrDefault("COMPLETED", 0.0));
    assertEquals(0.0, snapshot.inputSum());
  }

  @Test
  void allGaugesAreRegisteredWithStableTags() {
    var rows = mock(ObservabilityRepository.class);
    metrics = new DatabaseMetrics(rows, Duration.ofMinutes(15));
    var registry = new SimpleMeterRegistry();

    metrics.bindTo(registry);

    assertNotNull(registry.find("workbench.runs").tag("status", "COMPLETED").gauge());
    assertNotNull(
        registry
            .find("workbench.invocations")
            .tag("kind", "model")
            .tag("status", "COMPLETED")
            .gauge());
    assertNotNull(registry.find("workbench.status.unknown").tag("kind", "run").gauge());
    assertNotNull(registry.find("workbench.model.tokens.known").tag("direction", "input").gauge());
  }
}
