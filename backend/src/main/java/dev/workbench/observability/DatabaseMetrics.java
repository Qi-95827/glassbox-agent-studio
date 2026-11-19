package dev.workbench.observability;

import dev.workbench.domain.CheckpointSkipReason;
import dev.workbench.domain.InvocationStatus;
import dev.workbench.domain.RunStatus;
import dev.workbench.domain.StopCause;
import dev.workbench.evaluation.CaseStatus;
import dev.workbench.persistence.repository.ObservabilityRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.util.Arrays;
import java.util.List;
import java.util.function.DoubleSupplier;
import org.springframework.stereotype.Component;

/** 数据库快照指标；固定标签不包含任务 ID、用户信息或代码内容。 */
@Component
public class DatabaseMetrics implements MeterBinder {
  private final ObservabilityRepository rows;

  public DatabaseMetrics(ObservabilityRepository rows) {
    this.rows = rows;
  }

  @Override
  public void bindTo(MeterRegistry registry) {
    for (RunStatus status : RunStatus.values()) {
      register(
          registry,
          "workbench.runs",
          "status",
          status.name(),
          () -> rows.countRunsByStatus(status.name()));
    }
    for (InvocationStatus status : InvocationStatus.values()) {
      registerInvocation(
          registry, "agent", status, () -> rows.countAgentInvocationsByStatus(status.name()));
      registerInvocation(
          registry, "model", status, () -> rows.countModelInvocationsByStatus(status.name()));
      registerInvocation(
          registry, "tool", status, () -> rows.countToolInvocationsByStatus(status.name()));
    }
    for (CaseStatus status : CaseStatus.values()) {
      register(
          registry,
          "workbench.evaluation.cases",
          "status",
          status.name(),
          () -> rows.countEvaluationCasesByStatus(status.name()));
    }
    for (StopCause cause : StopCause.values()) {
      register(
          registry,
          "workbench.run.stop.cause",
          "cause",
          cause.name(),
          () -> rows.countRunsByStopCauseAndStatus(cause.name(), cause.status().name()));
    }
    register(
        registry,
        "workbench.run.stop.cause",
        "cause",
        "UNRECORDED",
        rows::countStoppedRunsWithoutStopCause);
    register(
        registry,
        "workbench.run.stop.cause",
        "cause",
        "INCONSISTENT",
        rows::countInconsistentStopCauses);
    for (CheckpointSkipReason reason : CheckpointSkipReason.values()) {
      register(
          registry,
          "workbench.checkpoint.skipped",
          "reason",
          reason.name(),
          () -> rows.countCheckpointSkipped(reason.name()));
    }
    List<String> knownReasons =
        Arrays.stream(CheckpointSkipReason.values()).map(Enum::name).toList();
    register(
        registry,
        "workbench.checkpoint.skipped",
        "reason",
        "UNKNOWN",
        () -> rows.countCheckpointSkippedUnknown(knownReasons));
    registerInputTokenMetrics(registry);
    registerOutputTokenMetrics(registry);
    registerModelDurationMetrics(registry);
    registerToolDurationMetrics(registry);
    List<String> runStatuses = Arrays.stream(RunStatus.values()).map(Enum::name).toList();
    List<String> invocationStatuses =
        Arrays.stream(InvocationStatus.values()).map(Enum::name).toList();
    List<String> caseStatuses = Arrays.stream(CaseStatus.values()).map(Enum::name).toList();
    registerUnknownStatus(registry, "run", () -> rows.countRunsWithUnknownStatus(runStatuses));
    registerUnknownStatus(
        registry, "agent", () -> rows.countAgentInvocationsWithUnknownStatus(invocationStatuses));
    registerUnknownStatus(
        registry, "model", () -> rows.countModelInvocationsWithUnknownStatus(invocationStatuses));
    registerUnknownStatus(
        registry, "tool", () -> rows.countToolInvocationsWithUnknownStatus(invocationStatuses));
    registerUnknownStatus(
        registry, "evaluation", () -> rows.countEvaluationCasesWithUnknownStatus(caseStatuses));
  }

  /** 只暴露未知数量，不把数据库中的未知编码变为高基数标签。 */
  private void registerUnknownStatus(
      MeterRegistry registry, String kind, DoubleSupplier supplier) {
    register(registry, "workbench.status.unknown", "kind", kind, supplier);
  }

  private void registerInvocation(
      MeterRegistry registry,
      String kind,
      InvocationStatus status,
      DoubleSupplier supplier) {
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
        () -> value(rows.modelUsage().inputSum()));
    register(
        registry,
        "workbench.model.tokens.known.calls",
        "direction",
        "input",
        () -> rows.modelUsage().inputKnownCalls());
    register(
        registry,
        "workbench.model.tokens.unknown.calls",
        "direction",
        "input",
        () -> rows.modelUsage().inputUnknownCalls());
  }

  private void registerOutputTokenMetrics(MeterRegistry registry) {
    register(
        registry,
        "workbench.model.tokens.known",
        "direction",
        "output",
        () -> value(rows.modelUsage().outputSum()));
    register(
        registry,
        "workbench.model.tokens.known.calls",
        "direction",
        "output",
        () -> rows.modelUsage().outputKnownCalls());
    register(
        registry,
        "workbench.model.tokens.unknown.calls",
        "direction",
        "output",
        () -> rows.modelUsage().outputUnknownCalls());
  }

  private void registerModelDurationMetrics(MeterRegistry registry) {
    Gauge.builder(
            "workbench.invocation.duration.known.seconds",
            this,
            ignored -> value(rows.modelUsage().durationSum()) / 1000.0)
        .tag("kind", "model")
        .register(registry);
    register(
        registry,
        "workbench.invocation.duration.unknown.calls",
        "kind",
        "model",
        () -> rows.modelUsage().durationUnknownCalls());
  }

  private void registerToolDurationMetrics(MeterRegistry registry) {
    Gauge.builder(
            "workbench.invocation.duration.known.seconds",
            this,
            ignored -> value(rows.toolUsage().durationSum()) / 1000.0)
        .tag("kind", "tool")
        .register(registry);
    register(
        registry,
        "workbench.invocation.duration.unknown.calls",
        "kind",
        "tool",
        () -> rows.toolUsage().durationUnknownCalls());
  }

  private void register(
      MeterRegistry registry,
      String name,
      String tag,
      String value,
      DoubleSupplier supplier) {
    Gauge.builder(name, this, ignored -> supplier.getAsDouble())
        .tag(tag, value)
        .register(registry);
  }

  private static double value(Long number) {
    return number == null ? 0 : number.doubleValue();
  }
}
