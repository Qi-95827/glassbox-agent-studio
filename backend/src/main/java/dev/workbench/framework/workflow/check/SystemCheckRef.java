package dev.workbench.framework.workflow.check;

import java.util.Objects;

/**
 * 受信系统检查的标识与版本。
 *
 * <p>标识只允许小写字母、数字和连字符，版本从 1 开始。标识或版本不在执行器目录中时必须显式拒绝，不能回退到 其他检查或按默认成功处理。
 */
public record SystemCheckRef(String id, int version) implements Comparable<SystemCheckRef> {
  public SystemCheckRef {
    if (id == null || !id.matches("[a-z0-9-]{1,64}") || version < 1)
      throw new IllegalArgumentException("系统检查标识或版本无效。");
  }

  public static SystemCheckRef of(String id, int version) {
    return new SystemCheckRef(Objects.requireNonNull(id), version);
  }

  @Override
  public String toString() {
    return id + "@" + version;
  }

  @Override
  public int compareTo(SystemCheckRef other) {
    int byId = id.compareTo(other.id);
    return byId != 0 ? byId : Integer.compare(version, other.version);
  }
}
