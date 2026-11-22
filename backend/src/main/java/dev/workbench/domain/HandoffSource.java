package dev.workbench.domain;

import com.fasterxml.jackson.annotation.JsonValue;

/** 交接内容的产生来源，用于区分调度指令与模型输出。 */
public enum HandoffSource {
  COORDINATOR("coordinator"),
  /** 系统根据实际运行状态生成的结果，不是模型回答。 */
  SYSTEM("system"),
  MOCK_MODEL("mock-model"),
  DEEPSEEK_FLASH("deepseek-flash"),
  /** 用户自带 Key 的模型连接输出。 */
  MODEL_CONNECTION("user-model");
  private final String code;

  HandoffSource(String code) {
    this.code = code;
  }

  @JsonValue
  public String code() {
    return code;
  }
}
