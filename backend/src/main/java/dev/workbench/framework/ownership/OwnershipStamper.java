package dev.workbench.framework.ownership;

/** 新建资源时写入服务端归属；由 ownership 模块实现。 */
public interface OwnershipStamper {
  void stamp(String resourceType, String resourceId);
}
