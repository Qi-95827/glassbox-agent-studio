package dev.workbench.domain;

/** 本地系统内受控工具类型，显示名称保持与历史调用记录兼容。 */
public enum ToolKind {
  /** 不访问实际仓库的模拟检查。 */
  SIMULATED_INSPECTION("模拟检查工具"),
  /** 读取用户授权的单个工作区文件。 */
  WORKSPACE_READ("读取授权文件"),
  /** 在授权的文件集合内搜索字面文本。 */
  WORKSPACE_SEARCH("搜索授权文件"),
  /** 写入用户授权的单个工作区文件。 */
  WORKSPACE_WRITE("修改授权文件"),
  /** 检索工作区知识库并返回带 BM25 / 向量 / RRF 证据的片段。 */
  KNOWLEDGE_SEARCH("检索知识库"),
  /** 回读本次运行被 L1 卸载的上下文产物原文。 */
  CONTEXT_ARTIFACT_READ("回读上下文产物"),
  /** 外部 MCP 工具；名称由适配层使用命名空间暴露，执行仍经过 Tool Gateway。 */
  MCP("MCP 外部工具");

  private final String recordName;

  ToolKind(String recordName) {
    this.recordName = recordName;
  }

  public String recordName() {
    return recordName;
  }
}
