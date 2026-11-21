package dev.workbench.framework.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.workbench.domain.EventType;
import dev.workbench.domain.ToolKind;
import dev.workbench.framework.agent.execution.AgentRuntime;
import dev.workbench.domain.WorkspaceVersionConflict;
import dev.workbench.framework.trace.GlassboxModels.GatewayCheck;
import dev.workbench.framework.trace.GlassboxModels.ToolCallTrace;
import dev.workbench.framework.trace.SensitiveDataMasker;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/** 统一检查实例权限、控制调用，并记录结构化结果。 */
public final class ToolGateway {
  private final ObjectMapper json;
  private final AgentRuntime runtime;
  private final AtomicReference<RuntimeException> failure;

  public ToolGateway(
      ObjectMapper json, AgentRuntime runtime, AtomicReference<RuntimeException> failure) {
    this.json = json;
    this.runtime = runtime;
    this.failure = failure;
  }

  /** 显式传入工具实现的服务端契约版本。 */
  public <T> String execute(
      ToolKind kind,
      int version,
      String fingerprint,
      ToolEffect effect,
      String start,
      Function<T, String> summary,
      Callable<T> action) {
    return execute(kind, version, fingerprint, effect, start, summary, () -> {}, action);
  }

  /** 版本由服务端指定；参数验证须无副作用，写动作异常不能推断为没有写入。 */
  public <T> String execute(
      ToolKind kind,
      int toolVersion,
      String fingerprint,
      ToolEffect effect,
      String start,
      Function<T, String> summary,
      Runnable validate,
      Callable<T> action) {
    return execute(kind, toolVersion, fingerprint, effect, start, summary, validate, null, action);
  }

  /**
   * 额外记录这次调用观察到的资源版本，作为只读工作区恢复的证据。
   *
   * <p>版本在动作成功后计算；失败或拒绝的调用不产生版本证据。
   */
  public <T> String execute(
      ToolKind kind,
      int toolVersion,
      String fingerprint,
      ToolEffect effect,
      String start,
      Function<T, String> summary,
      Runnable validate,
      Function<T, String> resourceVersion,
      Callable<T> action) {
    if (toolVersion < 1) throw new IllegalArgumentException("工具版本无效。");
    synchronized (runtime) {
      // 同一轮中的后续工具也不能越过此前的受控停止或结果未知状态。
      var stopped = failure.get();
      if (stopped != null) throw stopped;
      boolean actionStarted = false;
      var checks = new ArrayList<GatewayCheck>();
      try {
        var callId = runtime.beforeTool(kind, toolVersion, fingerprint);
        checks.add(
            new GatewayCheck(
                "OWNERSHIP_AND_SCOPE",
                "身份与归属鉴权",
                "PASS",
                "运行实例已通过服务端归属与工作区范围校验"));
        checks.add(
            new GatewayCheck(
                "TOOL_REGISTRY_GRANT",
                "工具注册与节点权限",
                "PASS",
                kind.name() + " v" + toolVersion + " 命中本节点授权工具集（" + effect.name() + "）"));
        checks.add(
            new GatewayCheck(
                "BUDGET_AND_LOOP",
                "预算与防循环检查",
                "PASS",
                "总工具限额、单工具限额与重复指纹均在阈值内"));
        Instant started = Instant.now();
        long nanos = System.nanoTime();
        runtime.event(EventType.TOOL_STARTED, start);
        T data;
        try {
          validate.run();
          checks.add(
              new GatewayCheck(
                  "PATH_WHITELIST",
                  "路径白名单与读写隔离",
                  "PASS",
                  effect == ToolEffect.WRITE ? "目标路径命中写授权白名单" : "目标路径命中只读授权范围"));
          checks.add(
              new GatewayCheck("ARGUMENT_SCHEMA", "参数 Schema 校验", "PASS", "入参格式与约束校验通过"));
        } catch (IllegalArgumentException e) {
          checks.add(
              new GatewayCheck(
                  "PATH_WHITELIST",
                  "路径白名单与读写隔离",
                  "DENY",
                  e.getMessage() == null ? "输入参数或路径不在授权范围内" : e.getMessage()));
          checks.add(
              new GatewayCheck(
                  "ARGUMENT_SCHEMA", "参数 Schema 校验", "DENY", "被工具网关前置校验拦截，未执行动作"));
          checks.add(new GatewayCheck("CANCEL_STATE", "取消与暂停检查", "SKIP", "前置校验未通过"));
          return rejected(
              callId,
              kind,
              toolVersion,
              effect,
              started,
              nanos,
              ToolResult.ErrorCode.INVALID_ARGUMENT,
              "工具输入不符合约束，请检查参数和授权范围后再决定是否重试。",
              fingerprint,
              checks);
        }
        runtime.check();
        checks.add(new GatewayCheck("CANCEL_STATE", "取消与暂停检查", "PASS", "运行未取消且未挂起"));
        try {
          actionStarted = true;
          data = action.call();
        } catch (WorkspaceVersionConflict e) {
          // 此异常的契约要求在文件替换之前报告版本冲突。
          actionStarted = false;
          checks.add(
              new GatewayCheck(
                  "VERSION_GUARD", "文件版本冲突检查", "DENY", "目标文件版本已变化，拒绝覆盖写入"));
          return rejected(
              callId,
              kind,
              toolVersion,
              effect,
              started,
              nanos,
              ToolResult.ErrorCode.STALE_VERSION,
              "文件版本已变化，请重新读取授权文件后再决定是否修改。",
              fingerprint,
              checks);
        } catch (IllegalArgumentException e) {
          if (effect == ToolEffect.WRITE) throw e;
          checks.add(
              new GatewayCheck(
                  "EXECUTION_GUARD",
                  "执行期参数边界校验",
                  "DENY",
                  e.getMessage() == null ? "执行期参数校验未通过" : e.getMessage()));
          return rejected(
              callId,
              kind,
              toolVersion,
              effect,
              started,
              nanos,
              ToolResult.ErrorCode.INVALID_ARGUMENT,
              "工具输入不符合约束，请检查参数和授权范围后再决定是否重试。",
              fingerprint,
              checks);
        }
        Instant finished = Instant.now();
        String value =
            json.writeValueAsString(
                new ToolResult<>(
                    2,
                    callId,
                    kind,
                    toolVersion,
                    ToolResult.Status.SUCCEEDED,
                    effect,
                    started,
                    finished,
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - nanos),
                    data,
                    null));
        String safeSummary = summary.apply(data);
        String observedVersion = resourceVersion == null ? null : resourceVersion.apply(data);
        runtime.event(EventType.TOOL_FINISHED, safeSummary);
        if (observedVersion == null) runtime.toolResult(safeSummary);
        else runtime.toolResult(safeSummary, observedVersion);
        recordTrace(callId, fingerprint, checks, value);
        return value;
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw recordFailure(effect, actionStarted, new IllegalStateException("工具执行被中断，结果可能未知。", e));
      } catch (RuntimeException e) {
        throw recordFailure(effect, actionStarted, e);
      } catch (JsonProcessingException e) {
        throw recordFailure(effect, actionStarted, new IllegalStateException("工具结果无法序列化。", e));
      } catch (Exception e) {
        throw recordFailure(effect, actionStarted, new IllegalStateException("工具执行失败。", e));
      }
    }
  }

  private RuntimeException recordFailure(
      ToolEffect effect, boolean actionStarted, RuntimeException error) {
    RuntimeException recorded =
        effect == ToolEffect.WRITE
                && actionStarted
                && !(error instanceof UncertainToolEffectException)
            ? new UncertainToolEffectException(error)
            : error;
    failure.set(recorded);
    return recorded;
  }

  private String rejected(
      UUID callId,
      ToolKind kind,
      int toolVersion,
      ToolEffect effect,
      Instant started,
      long nanos,
      ToolResult.ErrorCode code,
      String safeMessage,
      String fingerprint,
      List<GatewayCheck> checks)
      throws JsonProcessingException {
    var error = new ToolResult.Error(code, safeMessage, true);
    String value =
        json.writeValueAsString(
            new ToolResult<>(
                2,
                callId,
                kind,
                toolVersion,
                ToolResult.Status.REJECTED,
                effect,
                started,
                Instant.now(),
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - nanos),
                null,
                error));
    runtime.event(EventType.TOOL_REJECTED, safeMessage);
    runtime.toolRejected(safeMessage);
    recordTrace(callId, fingerprint, checks, value);
    return value;
  }

  private void recordTrace(
      UUID callId, String fingerprint, List<GatewayCheck> checks, String fullResult) {
    var safeArgs = SensitiveDataMasker.maskAndTruncate(fingerprint == null ? "{}" : fingerprint);
    var safeResult = SensitiveDataMasker.maskAndTruncate(fullResult);
    runtime.toolTrace(
        callId,
        new ToolCallTrace(
            safeArgs.text(), checks, safeResult.text(), safeArgs.truncated() || safeResult.truncated()));
  }
}
