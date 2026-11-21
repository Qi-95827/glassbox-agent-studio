package dev.workbench.domain;

import java.util.UUID;

/** 服务端生成稳定标识；不信任客户端提供的主键。 */
public final class WorkbenchIds {
  public static String newId() {
    return UUID.randomUUID().toString();
  }

  private WorkbenchIds() {}
}
