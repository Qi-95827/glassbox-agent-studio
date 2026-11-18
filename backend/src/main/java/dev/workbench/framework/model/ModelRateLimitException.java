package dev.workbench.framework.model;

/** 提供者明确拒绝该请求，且尚未返回任何生成内容。 */
public final class ModelRateLimitException extends RuntimeException {
  public static final String SAFE_MESSAGE = "模型服务明确拒绝了本次请求，原因是限流。";

  public ModelRateLimitException() {
    super(SAFE_MESSAGE);
  }
}
