package dev.workbench.domain;

import java.time.Instant;

/** 统一的 UTC ISO-8601 时间戳格式。 */
public final class WorkbenchTime {
  public static String now() {
    return Instant.now().toString();
  }

  private WorkbenchTime() {}
}
