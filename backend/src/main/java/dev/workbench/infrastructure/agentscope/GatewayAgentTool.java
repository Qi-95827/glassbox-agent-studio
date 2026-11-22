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
        () -> spec.action().execute(bound, context.runtime()::check));
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
