package dev.workbench.planning;

/** 候选确认提交后派发运行；由 orchestration 实现。 */
public interface CandidateRunDispatcher {
  void dispatchConfirmed(String runId);
}
