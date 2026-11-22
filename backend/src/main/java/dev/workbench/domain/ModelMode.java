package dev.workbench.domain;

import java.util.Arrays;

/** 用户为任务选择的结果整理模式，与单次模型调用类型分开。 */
public enum ModelMode {
  MOCK("mock"),
  DEEPSEEK("deepseek"),
  /** 用户自带 Key 的 OpenAI 兼容模型；具体连接由任务的 modelConnectionId 指定。 */
  USER("user");

  private final String code;

  ModelMode(String code) {
    this.code = code;
  }

  public String code() {
    return code;
  }

  public static ModelMode fromCode(String code) {
    return Arrays.stream(values())
        .filter(value -> value.code.equals(code))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("未知模型模式：" + code));
  }
}
