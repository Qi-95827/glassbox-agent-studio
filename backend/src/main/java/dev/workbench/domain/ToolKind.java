package dev.workbench.domain;

/** 本地系统内受控工具类型，显示名称保持与历史调用记录兼容。 */
public enum ToolKind {
  /** 不访问实际仓库的模拟检查。 */
  SIMULATED_INSPECTION("模拟检查工具"),
  /** 读取用户授权的单个工作区文件。 */
  WORKSPACE_READ("读取授权文件"),
  /** 写入用户授权的单个工作区文件。 */
  WORKSPACE_WRITE("修改授权文件");

  private final String recordName;

  ToolKind(String recordName) {
    this.recordName = recordName;
  }

  public String recordName() {
    return recordName;
  }
}
