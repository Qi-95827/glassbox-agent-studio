package dev.workbench.observability;

import dev.workbench.domain.CaseStatus;
import dev.workbench.domain.CheckpointSkipReason;
import dev.workbench.domain.InvocationStatus;
import dev.workbench.domain.RunStatus;
import dev.workbench.domain.StopCause;
import dev.workbench.persistence.repository.ObservabilityRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 数据库快照指标；固定标签不包含任务 ID、用户信息或代码内容。
 *
 * <p>Prometheus 每次抓取都会读取全部 Gauge。此前每个 Gauge 各自执行一次 COUNT，一次抓取要打二十多次 数据库。现在改为「按窗口复用的不可变快照」：同一窗口内的所有
 * Gauge 共享同一份计数，窗口过期后由 第一个读取者一次性刷新。抓取频率再高也只会按窗口节流，代价是指标最多滞后一个窗口（默认 15 秒）。
 */
@Component
public class DatabaseMetrics implements MeterBinder {
  private static final long DEFAULT_TTL_SECONDS = 15;

  private final ObservabilityRepository rows;
  private final long ttlNanos;
  private volatile Snapshot snapshot;
  private volatile long refreshedAt;

  @org.springframework.beans.factory.annotation.Autowired
  public DatabaseMetrics(
      ObservabilityRepository rows,
      @Value("${workbench.metrics.snapshot.ttl-seconds:15}") long ttlSeconds) {
    this(
        rows,
        java.time.Duration.ofSeconds(
            ttlSeconds < 0 ? DEFAULT_TTL_SECONDS : Math.min(3_600, ttlSeconds)));
  }

  /** 测试可直接指定窗口；{@code Duration.ZERO} 表示每次都重新计数。 */
  DatabaseMetrics(ObservabilityRepository rows, java.time.Duration ttl) {
    this.rows = rows;
    this.ttlNanos = Math.max(0, ttl == null ? 0 : ttl.toNanos());
  }

  @Override
  public void bindTo(MeterRegistry registry) {
    for (RunStatus status : RunStatus.values()) {
      register(
          registry,
          "workbench.runs",
          "status",
          status.name(),
          () -> count(snapshot().runs(), status.name()));
    }
    for (InvocationStatus status : InvocationStatus.values()) {
      registerInvocation(
          registry, "agent", status, () -> count(snapshot().agents(), status.name()));
      registerInvocation(
          registry, "model", status, () -> count(snapshot().models(), status.name()));
      registerInvocation(registry, "tool", status, () -> count(snapshot().tools(), status.name()));
    }
    for (CaseStatus status : CaseStatus.values()) {
      register(
          registry,
          "workbench.evaluation.cases",
          "status",
          status.name(),
          () -> count(snapshot().evaluationCases(), status.name()));
    }
    for (StopCause cause : StopCause.values()) {
      register(
          registry,
          "workbench.run.stop.cause",
          "cause",
          cause.name(),
          () -> count(snapshot().stopCauses(), cause.name()));
    }
    register(
        registry,
        "workbench.run.stop.cause",
        "cause",
        "UNRECORDED",
        () -> snapshot().stoppedWithoutCause());
    register(
        registry,
        "workbench.run.stop.cause",
        "cause",
        "INCONSISTENT",
        () -> snapshot().inconsistentStopCauses());
    for (CheckpointSkipReason reason : CheckpointSkipReason.values()) {
      register(
          registry,
          "workbench.checkpoint.skipped",
          "reason",
          reason.name(),
          () -> count(snapshot().checkpointSkipped(), reason.name()));
    }
    register(
        registry,
        "workbench.checkpoint.skipped",
        "reason",
        "UNKNOWN",
        () -> snapshot().checkpointSkippedUnknown());
    registerInputTokenMetrics(registry);
    registerOutputTokenMetrics(registry);
    registerModelDurationMetrics(registry);
    registerToolDurationMetrics(registry);
    register(registry, "workbench.status.unknown", "kind", "run", () -> snapshot().unknownRuns());
    register(
        registry, "workbench.status.unknown", "kind", "agent", () -> snapshot().unknownAgents());
    register(
        registry, "workbench.status.unknown", "kind", "model", () -> snapshot().unknownModels());
    register(registry, "workbench.status.unknown", "kind", "tool", () -> snapshot().unknownTools());
    register(
        registry,
        "workbench.status.unknown",
        "kind",
        "evaluation",
        () -> snapshot().unknownEvaluationCases());
  }

  /** 立即重新计数；供测试与运维强制刷新，不受窗口限制。 */
  public synchronized void refreshNow() {
    snapshot = load();
    refreshedAt = System.nanoTime();
  }

  /** 读取当前窗口快照；包级可见以便单元测试直接验证缓存窗口，无需经过 Micrometer Gauge。 */
  Snapshot snapshotForRead() {
    return snapshot();
  }

  /** 取当前窗口内的快照；窗口过期时由第一个读取者刷新，其余读取者复用同一份不可变数据。 */
  private Snapshot snapshot() {
    Snapshot current = snapshot;
    long now = System.nanoTime();
    if (current != null && ttlNanos > 0 && now - refreshedAt < ttlNanos) return current;
    synchronized (this) {
      current = snapshot;
      now = System.nanoTime();
      if (current != null && ttlNanos > 0 && now - refreshedAt < ttlNanos) return current;
      current = load();
      snapshot = current;
      refreshedAt = System.nanoTime();
      return current;
    }
  }

  private Snapshot load() {
    var runs = new HashMap<String, Double>();
    for (RunStatus status : RunStatus.values())
      runs.put(status.name(), numeric(rows.countRunsByStatus(status.name())));
    var agents = new HashMap<String, Double>();
    var models = new HashMap<String, Double>();
    var tools = new HashMap<String, Double>();
    for (InvocationStatus status : InvocationStatus.values()) {
      agents.put(status.name(), numeric(rows.countAgentInvocationsByStatus(status.name())));
      models.put(status.name(), numeric(rows.countModelInvocationsByStatus(status.name())));
      tools.put(status.name(), numeric(rows.countToolInvocationsByStatus(status.name())));
    }
    var cases = new HashMap<String, Double>();
    for (CaseStatus status : CaseStatus.values())
      cases.put(status.name(), numeric(rows.countEvaluationCasesByStatus(status.name())));
    var stopCauses = new HashMap<String, Double>();
    for (StopCause cause : StopCause.values())
      stopCauses.put(
          cause.name(),
          numeric(rows.countRunsByStopCauseAndStatus(cause.name(), cause.status().name())));
    var skipped = new HashMap<String, Double>();
    for (CheckpointSkipReason reason : CheckpointSkipReason.values())
      skipped.put(reason.name(), numeric(rows.countCheckpointSkipped(reason.name())));
    List<String> knownReasons =
        Arrays.stream(CheckpointSkipReason.values()).map(Enum::name).toList();
    List<String> runStatuses = Arrays.stream(RunStatus.values()).map(Enum::name).toList();
    List<String> invocationStatuses =
        Arrays.stream(InvocationStatus.values()).map(Enum::name).toList();
    List<String> caseStatuses = Arrays.stream(CaseStatus.values()).map(Enum::name).toList();
    var modelUsage = rows.modelUsage();
    var toolUsage = rows.toolUsage();
    return new Snapshot(
        Map.copyOf(runs),
        Map.copyOf(agents),
        Map.copyOf(models),
        Map.copyOf(tools),
        Map.copyOf(cases),
        Map.copyOf(stopCauses),
        numeric(rows.countStoppedRunsWithoutStopCause()),
        numeric(rows.countInconsistentStopCauses()),
        Map.copyOf(skipped),
        numeric(rows.countCheckpointSkippedUnknown(knownReasons)),
        modelUsage == null ? 0 : numeric(modelUsage.inputSum()),
        modelUsage == null ? 0 : numeric(modelUsage.inputKnownCalls()),
        modelUsage == null ? 0 : numeric(modelUsage.inputUnknownCalls()),
        modelUsage == null ? 0 : numeric(modelUsage.outputSum()),
        modelUsage == null ? 0 : numeric(modelUsage.outputKnownCalls()),
        modelUsage == null ? 0 : numeric(modelUsage.outputUnknownCalls()),
        modelUsage == null ? 0 : numeric(modelUsage.durationSum()),
        modelUsage == null ? 0 : numeric(modelUsage.durationUnknownCalls()),
        toolUsage == null ? 0 : numeric(toolUsage.durationSum()),
        toolUsage == null ? 0 : numeric(toolUsage.durationUnknownCalls()),
        numeric(rows.countRunsWithUnknownStatus(runStatuses)),
        numeric(rows.countAgentInvocationsWithUnknownStatus(invocationStatuses)),
        numeric(rows.countModelInvocationsWithUnknownStatus(invocationStatuses)),
        numeric(rows.countToolInvocationsWithUnknownStatus(invocationStatuses)),
        numeric(rows.countEvaluationCasesWithUnknownStatus(caseStatuses)));
  }

  private static double count(Map<String, Double> values, String key) {
    Double value = values.get(key);
    return value == null ? 0 : value;
  }

  private void registerInvocation(
      MeterRegistry registry,
      String kind,
      InvocationStatus status,
      java.util.function.DoubleSupplier supplier) {
    Gauge.builder("workbench.invocations", this, ignored -> supplier.getAsDouble())
        .description("当前保留记录中的实际调用数")
        .tag("kind", kind)
        .tag("status", status.name())
        .register(registry);
  }

  private void registerInputTokenMetrics(MeterRegistry registry) {
    register(
        registry,
        "workbench.model.tokens.known",
        "direction",
        "input",
        () -> snapshot().inputSum());
    register(
        registry,
        "workbench.model.tokens.known.calls",
        "direction",
        "input",
        () -> snapshot().inputKnownCalls());
    register(
        registry,
        "workbench.model.tokens.unknown.calls",
        "direction",
        "input",
        () -> snapshot().inputUnknownCalls());
  }

  private void registerOutputTokenMetrics(MeterRegistry registry) {
    register(
        registry,
        "workbench.model.tokens.known",
        "direction",
        "output",
        () -> snapshot().outputSum());
    register(
        registry,
        "workbench.model.tokens.known.calls",
        "direction",
        "output",
        () -> snapshot().outputKnownCalls());
    register(
        registry,
        "workbench.model.tokens.unknown.calls",
        "direction",
        "output",
        () -> snapshot().outputUnknownCalls());
  }

  private void registerModelDurationMetrics(MeterRegistry registry) {
    Gauge.builder(
            "workbench.invocation.duration.known.seconds",
            this,
            ignored -> snapshot().modelDurationSumMs() / 1000.0)
        .tag("kind", "model")
        .register(registry);
    register(
        registry,
        "workbench.invocation.duration.unknown.calls",
        "kind",
        "model",
        () -> snapshot().modelDurationUnknownCalls());
  }

  private void registerToolDurationMetrics(MeterRegistry registry) {
    Gauge.builder(
            "workbench.invocation.duration.known.seconds",
            this,
            ignored -> snapshot().toolDurationSumMs() / 1000.0)
        .tag("kind", "tool")
        .register(registry);
    register(
        registry,
        "workbench.invocation.duration.unknown.calls",
        "kind",
        "tool",
        () -> snapshot().toolDurationUnknownCalls());
  }

  private void register(
      MeterRegistry registry,
      String name,
      String tag,
      String value,
      java.util.function.DoubleSupplier supplier) {
    Gauge.builder(name, this, ignored -> supplier.getAsDouble()).tag(tag, value).register(registry);
  }

  private static double numeric(Long number) {
    return number == null ? 0 : number.doubleValue();
  }

  /** 一次刷新得到的不可变计数；所有 Gauge 只读它，不再各自查询数据库。 */
  record Snapshot(
      Map<String, Double> runs,
      Map<String, Double> agents,
      Map<String, Double> models,
      Map<String, Double> tools,
      Map<String, Double> evaluationCases,
      Map<String, Double> stopCauses,
      double stoppedWithoutCause,
      double inconsistentStopCauses,
      Map<String, Double> checkpointSkipped,
      double checkpointSkippedUnknown,
      double inputSum,
      double inputKnownCalls,
      double inputUnknownCalls,
      double outputSum,
      double outputKnownCalls,
      double outputUnknownCalls,
      double modelDurationSumMs,
      double modelDurationUnknownCalls,
      double toolDurationSumMs,
      double toolDurationUnknownCalls,
      double unknownRuns,
      double unknownAgents,
      double unknownModels,
      double unknownTools,
      double unknownEvaluationCases) {}
}
