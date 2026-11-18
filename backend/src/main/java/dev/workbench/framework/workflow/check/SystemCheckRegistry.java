package dev.workbench.framework.workflow.check;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 受信系统检查目录。
 *
 * <p>目录在启动时构建并拒绝重复引用。未注册的标识或版本不能执行，也不能回退到其他检查。历史计划中保存的 引用按原版本读取；删除执行器前必须评估仍可恢复的计划。
 */
public final class SystemCheckRegistry {
  private final Map<SystemCheckRef, SystemCheckExecutor> executors;

  public SystemCheckRegistry(List<SystemCheckExecutor> executors) {
    var checked = new LinkedHashMap<SystemCheckRef, SystemCheckExecutor>();
    for (var executor : executors) {
      Objects.requireNonNull(executor, "系统检查执行器不能为空");
      var ref = Objects.requireNonNull(executor.ref(), "系统检查引用不能为空");
      if (checked.putIfAbsent(ref, executor) != null)
        throw new IllegalStateException("系统检查标识与版本重复：" + ref);
    }
    this.executors = Map.copyOf(checked);
  }

  /** 解析已注册的检查；标识或版本不受支持时显式报错。 */
  public SystemCheckExecutor resolve(SystemCheckRef ref) {
    var executor = executors.get(ref);
    if (executor == null) throw new IllegalArgumentException("计划引用了未注册的系统检查：" + ref + "。");
    return executor;
  }

  /** 仅用于规划和状态核对，不执行检查。 */
  public boolean supports(SystemCheckRef ref) {
    return ref != null && executors.containsKey(ref);
  }

  public List<SystemCheckRef> references() {
    return executors.keySet().stream().sorted().toList();
  }
}
