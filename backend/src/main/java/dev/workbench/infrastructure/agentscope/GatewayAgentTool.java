package dev.workbench.infrastructure.agentscope;

import dev.workbench.domain.ToolKind;
import dev.workbench.framework.tool.*;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** 项目注册给 AgentScope 的唯一工具包装，绑定服务端确认的类型与版本。 */
final class GatewayAgentTool<T> extends ToolBase {
  private final ToolKind kind;
  private final int version;
  private final ToolEffect effect;
  private final ToolSpecification<T> spec;
  private final AgentToolContext context;
  private final ToolGateway gateway;

  GatewayAgentTool(
      ToolKind kind, int version, ToolSpecification<T> spec, AgentToolContext context) {
    super(
        ToolBase.builder()
            .name(spec.name())
            .description(spec.description())
            .inputSchema(inputSchema(spec))
            .concurrencySafe(false)
            .readOnly(ToolContracts.effectFor(kind) == ToolEffect.READ_ONLY));
    this.kind = kind;
    this.version = version;
    this.effect = ToolContracts.effectFor(kind);
    this.spec = spec;
    this.context = context;
    this.gateway = new ToolGateway(context.json(), context.runtime(), context.failure());
  }

  @Override
  public Mono<ToolResultBlock> callAsync(ToolCallParam call) {
    // 工作区 I/O 和模拟等待使用阻塞线程，不占用 SDK 的事件循环。
    return Mono.fromCallable(
            () ->
                ToolResultBlock.text(invoke(call.getInput()))
                    .withIdAndName(call.getToolUseBlock().getId(), getName()))
        .subscribeOn(Schedulers.boundedElastic());
  }

  private String invoke(Map<String, Object> input) throws Exception {
    var arguments = new LinkedHashMap<String, String>();
    var fingerprintParts = new ArrayList<>(spec.fingerprintPrefix());
    for (var parameter : spec.parameters()) {
      Object value = input.get(parameter.name());
      String argument = value instanceof String text ? text : null;
      arguments.put(parameter.name(), argument);
      fingerprintParts.add(argument);
    }
    var bound = java.util.Collections.unmodifiableMap(arguments);
    var boundRef = new java.util.concurrent.atomic.AtomicReference<>(bound);
    java.util.function.Function<Map<String, Object>, Map<String, String>> toBound =
        edited -> {
          var editedArguments = new LinkedHashMap<String, String>();
          for (var parameter : spec.parameters()) {
            Object value = edited.get(parameter.name());
            if (!(value instanceof String text))
              throw new IllegalArgumentException("工具参数与声明不符：" + parameter.name());
            editedArguments.put(parameter.name(), text);
          }
          if (!edited.keySet().equals(editedArguments.keySet()))
            throw new IllegalArgumentException("工具参数与声明不符。");
          return java.util.Collections.unmodifiableMap(editedArguments);
        };
    java.util.function.Function<T, String> summaryFn =
        value -> spec.summarize().apply(boundRef.get(), value);
    java.util.function.Function<T, String> resourceVersionFn =
        spec.resourceVersion() == null
            ? null
            : value -> spec.resourceVersion().apply(boundRef.get(), value);
    java.util.function.Consumer<String> editedValidate =
        editedJson -> {
          try {
            var edited = toBound.apply(context.json().readValue(editedJson, Map.class));
            spec.validate().accept(edited);
            boundRef.set(edited);
          } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException("修改后的参数不是有效 JSON。", e);
          }
        };
    java.util.function.Function<String, T> editedAction =
        editedJson -> {
          try {
            var edited = toBound.apply(context.json().readValue(editedJson, Map.class));
            boundRef.set(edited);
            return (T) spec.action().execute(edited, context.runtime()::check);
          } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException("修改后的参数不是有效 JSON。", e);
          } catch (RuntimeException e) {
            throw e;
          } catch (Exception e) {
            throw new IllegalStateException("改参工具执行失败。", e);
          }
        };
    return gateway.execute(
        kind,
        version,
        ToolFingerprint.of(kind, fingerprintParts.toArray(String[]::new)),
        context.json().writeValueAsString(input),
        effect,
        spec.startMessage(),
        summaryFn,
        () -> {
          if (!input.keySet().equals(arguments.keySet()) || arguments.containsValue(null))
            throw new IllegalArgumentException("工具参数与声明不符。");
          spec.validate().accept(bound);
        },
        resourceVersionFn,
        editedValidate,
        editedAction,
        targetPaths(effect, input),
        () -> spec.action().execute(bound, context.runtime()::check));
  }

  /**
   * 收集本次调用的真实写入目标：授权文件清单 + 入参中的 path。
   *
   * <p>只读调用不产生文件写入，直接返回空列表；写入调用把单文件闭包（授权清单即目标）与多文件入参 一并交给风险策略，避免只靠参数正文猜测目标。
   */
  /**
   * 收集本次调用的**真实写入目标**。
   *
   * <p>多文件写入用入参 {@code path} 定位本次目标；单文件闭包写入入参没有 path，才使用本次节点的写授权 路径。只读授权文件（如任务声明的 {@code
   * pom.xml}）绝不能混入写入目标，否则普通代码写入会被 误判为高危并触发不必要的审批。
   */
  private java.util.List<String> targetPaths(ToolEffect effect, Map<String, Object> input) {
    if (effect != ToolEffect.WRITE) return java.util.List.of();
    Object path = input == null ? null : input.get("path");
    return writeTargets(path instanceof String text ? text : null, context.writePaths());
  }

  /**
   * 写入目标路径规则：多文件入参以 {@code path} 为准；单文件闭包才用节点写授权。
   *
   * <p>只读授权文件（任务声明的 {@code pom.xml}、{@code .env} 等）绝不能混入写入目标，否则一次普通代码 写入会被误判为高危并触发不必要的 Gate 5.5 审批。
   */
  static java.util.List<String> writeTargets(String inputPath, java.util.List<String> writePaths) {
    if (inputPath != null && !inputPath.isBlank()) return java.util.List.of(inputPath);
    if (writePaths == null || writePaths.isEmpty()) return java.util.List.of();
    return java.util.List.copyOf(new java.util.LinkedHashSet<>(writePaths));
  }

  private static Map<String, Object> inputSchema(ToolSpecification<?> specification) {
    Map<String, Object> properties = new LinkedHashMap<>();
    for (var parameter : specification.parameters()) {
      properties.put(
          parameter.name(), Map.of("type", "string", "description", parameter.description()));
    }
    return Map.of(
        "type",
        "object",
        "properties",
        Map.copyOf(properties),
        "required",
        specification.parameters().stream().map(ToolSpecification.Parameter::name).toList(),
        "additionalProperties",
        false);
  }
}
