package dev.workbench.planning;

/** 候选计划的生命周期；GENERATING 之外都是已结算状态。 */
public enum PlanCandidateStatus {
  /** 已持久化请求，模型还在生成。 */
  GENERATING,
  /** 通过服务端校验，可进入确认和运行创建。 */
  READY,
  /** 模型返回了结构，但违反节点、依赖、文件或角色规则。 */
  INVALID,
  /** 模型未返回、无法解析或服务不可用；保留原因供重试。 */
  FAILED,
  /** 用户在生成中主动取消；晚到的模型结果不能改写。 */
  CANCELLED,
  /** 进程中断或重启时未结算的候选按中断处理。 */
  INTERRUPTED,
  /** 同组出现新的输入版本或重新生成，旧候选不能再确认。 */
  STALE,
  /** 已被某个运行确认消费；本期只保留状态，运行创建在任务 2 接通。 */
  CONSUMED;

  public boolean generating() {
    return this == GENERATING;
  }

  /** 只有 READY 可以进入确认；其余状态都必须重新生成。 */
  public boolean usable() {
    return this == READY;
  }
}
