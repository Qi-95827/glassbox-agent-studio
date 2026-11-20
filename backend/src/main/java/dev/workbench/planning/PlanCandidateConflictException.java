package dev.workbench.planning;

/** 同一请求标识被用于不同输入时返回冲突，提示客户端换标识或先读取原候选。 */
public class PlanCandidateConflictException extends RuntimeException {
  public PlanCandidateConflictException(String message) {
    super(message);
  }
}
