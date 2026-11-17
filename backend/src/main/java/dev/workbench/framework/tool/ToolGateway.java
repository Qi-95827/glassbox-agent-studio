package dev.workbench.framework.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.workbench.domain.EventType;
import dev.workbench.domain.ToolKind;
import dev.workbench.framework.agent.execution.AgentRuntime;
import dev.workbench.workspace.WorkspaceVersionConflict;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/** 包装工具的控制、记录和结构化结果；权限仍由具体工具与服务端校验。 */
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
    if (toolVersion < 1) throw new IllegalArgumentException("工具版本无效。");
    synchronized (runtime) {
      try {
        var callId = runtime.beforeTool(kind, toolVersion, fingerprint);
        Instant started = Instant.now();
        long nanos = System.nanoTime();
        runtime.event(EventType.TOOL_STARTED, start);
        T data;
        try {
          validate.run();
        } catch (IllegalArgumentException e) {
          return rejected(
              callId,
              kind,
              toolVersion,
              effect,
              started,
              nanos,
              ToolResult.ErrorCode.INVALID_ARGUMENT,
              "工具输入不符合约束，请检查参数和授权范围后再决定是否重试。");
        }
        try {
          data = action.call();
        } catch (WorkspaceVersionConflict e) {
          return rejected(
              callId,
              kind,
              toolVersion,
              effect,
              started,
              nanos,
              ToolResult.ErrorCode.STALE_VERSION,
              "文件版本已变化，请重新读取授权文件后再决定是否修改。");
        } catch (IllegalArgumentException e) {
          if (effect == ToolEffect.WRITE) throw new UncertainToolEffectException(e);
          return rejected(
              callId,
              kind,
              toolVersion,
              effect,
              started,
              nanos,
              ToolResult.ErrorCode.INVALID_ARGUMENT,
              "工具输入不符合约束，请检查参数和授权范围后再决定是否重试。");
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
        runtime.event(EventType.TOOL_FINISHED, safeSummary);
        runtime.toolResult(safeSummary);
        return value;
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        var error = new IllegalStateException("工具执行被中断，结果可能未知。", e);
        failure.set(error);
        throw error;
      } catch (RuntimeException e) {
        failure.set(e);
        throw e;
      } catch (JsonProcessingException e) {
        var error = new IllegalStateException("工具结果无法序列化，写操作状态需检查。", e);
        failure.set(error);
        throw error;
      } catch (Exception e) {
        var error = new IllegalStateException("工具执行失败，写操作状态需检查。", e);
        failure.set(error);
        throw error;
      }
    }
  }

  private String rejected(
      UUID callId,
      ToolKind kind,
      int toolVersion,
      ToolEffect effect,
      Instant started,
      long nanos,
      ToolResult.ErrorCode code,
      String safeMessage)
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
    return value;
  }
}
