package dev.workbench.domain;

/** 运行阻塞原因编码；语义稳定，供 API 与界面共享。 */
public final class BlockReason {
  public static final String REPRODUCTION_MISSING_INFO = "REPRODUCTION_MISSING_INFO";

  private BlockReason() {}
}
