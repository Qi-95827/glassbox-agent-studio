package dev.workbench.planning;

/**
 * 规划模型适配边界。
 *
 * <p>只交换用户已确认的任务输入和规则；不创建执行 Agent、不签发工具授权，也不把模型文字 当作用户审批。自动化测试可以替换实现，生产实现使用独立模型调用。
 */
public interface PlanCandidateModel {
  /**
   * @param instructions 固定规划规则，包含协议与安全边界
   * @param input 已确认的任务输入快照（JSON）
   * @return 模型原文及可获得的用量；没有回传用量时保持 null，不能记为 0
   */
  Reply propose(String instructions, String input);

  record Reply(String text, String modelName, Integer inputTokens, Integer outputTokens) {}
}
