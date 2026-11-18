package dev.workbench.framework.workflow.check;

/**
 * 受信代码注册的确定性检查。
 *
 * <p>执行器只能依据 {@link SystemCheckRequest} 中的已结算输入作出判断，不得调用模型、访问网络或产生外部 副作用。实现方在启动时声明唯一引用和版本。
 */
public interface SystemCheckExecutor {
  /** 本执行器支持的标识与版本。 */
  SystemCheckRef ref();

  /** 执行检查并返回结算结果；异常表示检查未能判定，由调度器按失败处理。 */
  SystemCheckResult run(SystemCheckRequest request);
}
