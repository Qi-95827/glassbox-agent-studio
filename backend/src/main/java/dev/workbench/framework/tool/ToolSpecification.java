package dev.workbench.framework.tool;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Consumer;

/** 受信工具的参数与业务动作。执行器负责创建 SDK 包装器和控制调用。 */
public record ToolSpecification<T>(
    String name,
    String description,
    List<Parameter> parameters,
    List<String> fingerprintPrefix,
    String startMessage,
    Consumer<Map<String, String>> validate,
    Consumer<Map<String, String>> scopeValidate,
    Action<T> action,
    BiFunction<Map<String, String>, T, String> summarize,
    BiFunction<Map<String, String>, T, String> resourceVersion) {
  /** 兼容只声明参数校验的工具；路径白名单校验另行通过 {@code scopeValidate} 声明。 */
  public ToolSpecification(
      String name,
      String description,
      List<Parameter> parameters,
      List<String> fingerprintPrefix,
      String startMessage,
      Consumer<Map<String, String>> validate,
      Action<T> action,
      BiFunction<Map<String, String>, T, String> summarize,
      BiFunction<Map<String, String>, T, String> resourceVersion) {
    this(
        name,
        description,
        parameters,
        fingerprintPrefix,
        startMessage,
        validate,
        null,
        action,
        summarize,
        resourceVersion);
  }

  public ToolSpecification(
      String name,
      String description,
      List<Parameter> parameters,
      List<String> fingerprintPrefix,
      String startMessage,
      Consumer<Map<String, String>> validate,
      Action<T> action,
      BiFunction<Map<String, String>, T, String> summarize) {
    this(
        name,
        description,
        parameters,
        fingerprintPrefix,
        startMessage,
        validate,
        null,
        action,
        summarize,
        null);
  }

  /** 同时声明参数 Schema 与路径白名单两道校验；二者失败必须分别归因，不能互相冒充。 */
  public ToolSpecification<T> withScopeValidate(Consumer<Map<String, String>> scopeValidate) {
    return new ToolSpecification<>(
        name,
        description,
        parameters,
        fingerprintPrefix,
        startMessage,
        validate,
        scopeValidate,
        action,
        summarize,
        resourceVersion);
  }

  public ToolSpecification {
    if (name == null
        || !name.matches("[A-Za-z0-9_-]{1,64}")
        || description == null
        || description.isBlank()
        || startMessage == null
        || startMessage.isBlank()) throw new IllegalArgumentException("工具名称、描述或开始说明无效。");
    parameters = List.copyOf(parameters);
    fingerprintPrefix = List.copyOf(fingerprintPrefix);
    var names = new HashSet<String>();
    for (var parameter : parameters) {
      if (!names.add(parameter.name())) throw new IllegalArgumentException("工具参数名称重复。");
    }
    Objects.requireNonNull(validate, "缺少工具参数校验");
    Objects.requireNonNull(action, "缺少工具业务动作");
    Objects.requireNonNull(summarize, "缺少工具结果摘要");
  }

  /** 现有工具契约中的必填字符串参数。 */
  public record Parameter(String name, String description) {
    public Parameter {
      if (name == null
          || !name.matches("[A-Za-z][A-Za-z0-9_]{0,63}")
          || description == null
          || description.isBlank()) throw new IllegalArgumentException("工具参数声明无效。");
    }
  }

  /** check 用于长动作定期检查运行取消、暂停和截止时间。 */
  @FunctionalInterface
  public interface Action<T> {
    T execute(Map<String, String> arguments, Runnable check) throws Exception;
  }
}
