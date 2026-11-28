package dev.workbench.framework.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.workbench.domain.EventType;
import dev.workbench.domain.ToolKind;
import dev.workbench.domain.WorkspaceVersionConflict;
import dev.workbench.framework.agent.execution.AgentRuntime;
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
  private final ToolRiskPolicy riskPolicy;

  public ToolGateway(
      ObjectMapper json, AgentRuntime runtime, AtomicReference<RuntimeException> failure) {
    this(json, runtime, failure, new ConservativeToolRiskPolicy());
  }

  public ToolGateway(
      ObjectMapper json,
      AgentRuntime runtime,
      AtomicReference<RuntimeException> failure,
      ToolRiskPolicy riskPolicy) {
    this.json = json;
    this.runtime = runtime;
    this.failure = failure;
    this.riskPolicy = riskPolicy == null ? new ConservativeToolRiskPolicy() : riskPolicy;
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
    return execute(
        kind,
        toolVersion,
        fingerprint,
        fingerprint,
        effect,
        start,
        summary,
        validate,
        resourceVersion,
        null,
        null,
        action);
  }

  /**
   * 带 Gate 5.5 的完整执行入口。
   *
   * <p>{@code argsJson} 只用于风险分级与审批单展示；{@code editedValidate} 和 {@code editedAction} 让“改参放行”重新经过
   * Gate 5 授权交集校验后再执行。
   */
  public <T> String execute(
      ToolKind kind,
      int toolVersion,
      String fingerprint,
      String argsJson,
      ToolEffect effect,
      String start,
      Function<T, String> summary,
      Runnable validate,
      Function<T, String> resourceVersion,
      java.util.function.Consumer<String> editedValidate,
      java.util.function.Function<String, T> editedAction,
      Callable<T> action) {

    return execute(
        kind,
        kind.name(),
        toolVersion,
        fingerprint,
        argsJson,
        effect,
        start,
        summary,
        validate,
        resourceVersion,
        editedValidate,
        editedAction,
        action);
  }

  /** 带明确目标路径的版本（工具名默认取枚举名），供内置写入工具注入真实写入目标。 */
  public <T> String execute(
      ToolKind kind,
      int toolVersion,
      String fingerprint,
      String argsJson,
      ToolEffect effect,
      String start,
      Function<T, String> summary,
      Runnable validate,
      Function<T, String> resourceVersion,
      java.util.function.Consumer<String> editedValidate,
      java.util.function.Function<String, T> editedAction,
      java.util.List<String> targetPaths,
      Callable<T> action) {
    return execute(
        kind,
        kind.name(),
        toolVersion,
        fingerprint,
        argsJson,
        effect,
        start,
        summary,
        validate,
        resourceVersion,
        editedValidate,
        editedAction,
        targetPaths,
        action);
  }

  /** 保留动态注册工具的逻辑名称，供网关证据和风险策略使用。 */
  public <T> String execute(
      ToolKind kind,
      String toolName,
      int toolVersion,
      String fingerprint,
      String argsJson,
      ToolEffect effect,
      String start,
      Function<T, String> summary,
      Runnable validate,
      Function<T, String> resourceVersion,
      java.util.function.Consumer<String> editedValidate,
      java.util.function.Function<String, T> editedAction,
      Callable<T> action) {
    return execute(
        kind,
        toolName,
        toolVersion,
        fingerprint,
        argsJson,
        effect,
        start,
        summary,
        validate,
        resourceVersion,
        editedValidate,
        editedAction,
        java.util.List.of(),
        action);
  }

  /**
   * 带明确目标路径的完整执行入口。
   *
   * <p>风险策略需要真实写入目标才能判断是否命中敏感文件；只靠参数 JSON 文本匹配会被代码正文中的 {@code security} 之类字样误伤，也会漏掉单文件闭包写入。
   */
  public <T> String execute(
      ToolKind kind,
      String toolName,
      int toolVersion,
      String fingerprint,
      String argsJson,
      ToolEffect effect,
      String start,
      Function<T, String> summary,
      Runnable validate,
      Function<T, String> resourceVersion,
      java.util.function.Consumer<String> editedValidate,
      java.util.function.Function<String, T> editedAction,
      java.util.List<String> targetPaths,
      Callable<T> action) {
    return execute(
        kind,
        toolName,
        toolVersion,
        fingerprint,
        argsJson,
        effect,
        start,
        summary,
        null,
        validate,
        resourceVersion,
        editedValidate,
        editedAction,
        targetPaths,
        action);
  }

  /**
   * 参数 Schema 与路径白名单分离的完整入口。
   *
   * <p>{@code scopeValidate} 为 null 表示该工具没有路径参数：此时 PATH_WHITELIST 记为 SKIP，而不是伪造 PASS。
   * 两道校验各自归因：路径越界只记 PATH_WHITELIST DENY，参数类型错误只记 ARGUMENT_SCHEMA DENY。
   */
  public <T> String execute(
      ToolKind kind,
      String toolName,
      int toolVersion,
      String fingerprint,
      String argsJson,
      ToolEffect effect,
      String start,
      Function<T, String> summary,
      Runnable scopeValidate,
      Runnable validate,
      Function<T, String> resourceVersion,
      java.util.function.Consumer<String> editedValidate,
      java.util.function.Function<String, T> editedAction,
      java.util.List<String> targetPaths,
      Callable<T> action) {
    if (toolVersion < 1) throw new IllegalArgumentException("工具版本无效。");
    synchronized (runtime) {
      // 同一轮中的后续工具也不能越过此前的受控停止或结果未知状态。
      var stopped = failure.get();
      if (stopped != null) throw stopped;
      boolean actionStarted = false;
      var checks = new ArrayList<GatewayCheck>();
      // 指纹仅用于防循环查重；玻璃盒轨迹必须记录实际生效的工具参数 JSON（人工改参后记录最终生效版本）。
      String traceArgs =
          argsJson != null && !argsJson.isBlank()
              ? argsJson
              : (fingerprint == null ? "{}" : fingerprint);
      try {
        var callId = runtime.beforeTool(kind, toolVersion, fingerprint);
        addCheck(toolName, checks, 
            new GatewayCheck("OWNERSHIP_AND_SCOPE", "身份与归属鉴权", "PASS", "运行实例已通过服务端归属与工作区范围校验"));
        addCheck(toolName, checks, 
            new GatewayCheck(
                "TOOL_REGISTRY_GRANT",
                "工具注册与节点权限",
                "PASS",
                toolName + " v" + toolVersion + " 命中本节点授权工具集（" + effect.name() + "）"));
        addCheck(toolName, checks, 
            new GatewayCheck("BUDGET_AND_LOOP", "预算与防循环检查", "PASS", "总工具限额、单工具限额与重复指纹均在阈值内"));
        Instant started = Instant.now();
        long nanos = System.nanoTime();
        runtime.event(EventType.TOOL_STARTED, start);
        T data;
        try {
          validate.run();
          addCheck(toolName, checks, new GatewayCheck("ARGUMENT_SCHEMA", "参数 Schema 校验", "PASS", "入参格式与约束校验通过"));
        } catch (IllegalArgumentException e) {
          addCheck(toolName, checks,
              new GatewayCheck(
                  "ARGUMENT_SCHEMA",
                  "参数 Schema 校验",
                  "DENY",
                  e.getMessage() == null ? "入参不符合工具声明" : e.getMessage()));
          addCheck(toolName, checks,
              new GatewayCheck(
                  "PATH_WHITELIST",
                  "路径白名单与读写隔离",
                  "SKIP",
                  scopeValidate == null ? "该工具没有工作区路径参数，路径白名单不适用" : "参数校验未通过，未进入路径白名单校验"));
          addCheck(toolName, checks, new GatewayCheck("CANCEL_STATE", "取消与暂停检查", "SKIP", "前置校验未通过"));
          return rejected(
              callId,
              kind,
              toolVersion,
              effect,
              started,
              nanos,
              ToolResult.ErrorCode.INVALID_ARGUMENT,
              "工具输入不符合参数约束，请检查参数后再决定是否重试。",
              traceArgs,
              checks);
        }
        if (scopeValidate == null) {
          addCheck(toolName, checks,
              new GatewayCheck(
                  "PATH_WHITELIST",
                  "路径白名单与读写隔离",
                  "SKIP",
                  "该工具没有工作区路径参数，路径白名单不适用"));
        } else {
          try {
            scopeValidate.run();
            addCheck(toolName, checks,
                new GatewayCheck(
                    "PATH_WHITELIST",
                    "路径白名单与读写隔离",
                    "PASS",
                    effect == ToolEffect.WRITE ? "目标路径命中写授权白名单" : "目标路径命中只读授权范围"));
          } catch (IllegalArgumentException scopeError) {
            addCheck(toolName, checks,
                new GatewayCheck(
                    "PATH_WHITELIST",
                    "路径白名单与读写隔离",
                    "DENY",
                    scopeError.getMessage() == null ? "路径不在授权范围内" : scopeError.getMessage()));
            addCheck(toolName, checks, new GatewayCheck("CANCEL_STATE", "取消与暂停检查", "SKIP", "前置校验未通过"));
            return rejected(
                callId,
                kind,
                toolVersion,
                effect,
                started,
                nanos,
                ToolResult.ErrorCode.INVALID_ARGUMENT,
                "工具路径不在任务授权范围内，未执行动作；请改用已授权文件。",
                traceArgs,
                checks);
          }
        }
        ToolRiskAssessment risk =
            riskPolicy.assess(new ToolRiskContext(toolName, effect, argsJson, targetPaths));
        addCheck(toolName, checks, new GatewayCheck("RISK_ASSESSMENT", "风险分级", risk.level().name(), risk.reason()));
        ToolApprovalDecision approval =
            risk.requiresApproval()
                ? runtime.approvalDecision(toolName, toolVersion, argsJson, risk)
                : ToolApprovalDecision.approve();
        addCheck(toolName, checks, 
            new GatewayCheck(
                "DYNAMIC_APPROVAL",
                "Gate 5.5 动态审批",
                approval.rejected() ? "DENY" : "PASS",
                approval.rejected()
                    ? (approval.reason() == null ? "用户拒绝执行该工具" : approval.reason())
                    : risk.requiresApproval() ? "用户已批准高危工具调用" : "风险低于审批阈值"));
        if (approval.rejected()) {
          actionStarted = false;
          return rejected(
              callId,
              kind,
              toolVersion,
              effect,
              started,
              nanos,
              ToolResult.ErrorCode.APPROVAL_REJECTED,
              approval.reason() == null ? "用户拒绝执行该工具，请调整方案后继续。" : "用户拒绝执行该工具：" + approval.reason(),
              traceArgs,
              checks);
        }
        runtime.check();
        addCheck(toolName, checks, new GatewayCheck("CANCEL_STATE", "取消与暂停检查", "PASS", "运行未取消且未挂起"));
        try {
          actionStarted = true;
          if (approval.edited()) {
            if (approval.editedArgsJson() != null && !approval.editedArgsJson().isBlank()) {
              traceArgs = approval.editedArgsJson();
            }
            if (editedValidate == null || editedAction == null)
              return rejected(
                  callId,
                  kind,
                  toolVersion,
                  effect,
                  started,
                  nanos,
                  ToolResult.ErrorCode.INVALID_ARGUMENT,
                  "当前工具不支持改参放行，请按原参数重新审批。",
                  traceArgs,
                  checks);
            try {
              // 改参放行必须重新经过 Gate 5 的文件/作用域授权交集复验，防止绕过。
              editedValidate.accept(approval.editedArgsJson());
              addCheck(toolName, checks, 
                  new GatewayCheck(
                      "EDITED_ARGS_REVALIDATION", "改参后二次 Gate 5 复验", "PASS", "修改后的参数仍属于节点授权范围"));
            } catch (IllegalArgumentException invalid) {
              addCheck(toolName, checks, 
                  new GatewayCheck(
                      "EDITED_ARGS_REVALIDATION",
                      "改参后二次 Gate 5 复验",
                      "DENY",
                      invalid.getMessage() == null ? "修改后的参数超出授权范围" : invalid.getMessage()));
              return rejected(
                  callId,
                  kind,
                  toolVersion,
                  effect,
                  started,
                  nanos,
                  ToolResult.ErrorCode.INVALID_ARGUMENT,
                  "修改后的参数未通过授权复验，工具未执行。",
                  traceArgs,
                  checks);
            }
            data = editedAction.apply(approval.editedArgsJson());
          } else {
            data = action.call();
          }
        } catch (WorkspaceVersionConflict e) {
          // 此异常的契约要求在文件替换之前报告版本冲突。
          actionStarted = false;
          addCheck(toolName, checks, new GatewayCheck("VERSION_GUARD", "文件版本冲突检查", "DENY", "目标文件版本已变化，拒绝覆盖写入"));
          return rejected(
              callId,
              kind,
              toolVersion,
              effect,
              started,
              nanos,
              ToolResult.ErrorCode.STALE_VERSION,
              "文件版本已变化，请重新读取授权文件后再决定是否修改。",
              traceArgs,
              checks);
        } catch (IllegalArgumentException e) {
          if (effect == ToolEffect.WRITE) throw e;
          addCheck(toolName, checks, 
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
              traceArgs,
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
        recordTrace(callId, traceArgs, checks, value);
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

  /**
   * 记录单道安检门判定，并即时推送同一条事实。
   *
   * <p>持久化的 {@code ToolCallTrace} 仍是唯一真相；这里只让正在观看的用户不必等到下一次
   * 轮询才看到关卡点亮。推送失败不影响安全检查本身，因此不参与异常处理。
   *
   * @param toolName 工具逻辑名；风险策略与审计都以它为准。
   * @param checks 本次调用的判定累积表。
   * @param check 刚产生的判定。
   */
  private void addCheck(String toolName, List<GatewayCheck> checks, GatewayCheck check) {
    checks.add(check);
    runtime.toolGateProgress(toolName, check);
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
      UUID callId, String inputArgs, List<GatewayCheck> checks, String fullResult) {
    var safeArgs =
        dev.workbench.framework.trace.JsonLeafMasker.maskAndTruncate(
            json, inputArgs == null ? "{}" : inputArgs, SensitiveDataMasker.DEFAULT_MAX_CHARS);
    var safeResult = SensitiveDataMasker.maskAndTruncate(fullResult);
    runtime.toolTrace(
        callId,
        new ToolCallTrace(
            safeArgs.text(),
            checks,
            safeResult.text(),
            safeArgs.truncated() || safeResult.truncated()));
  }
}
