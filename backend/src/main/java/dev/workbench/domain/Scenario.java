package dev.workbench.domain;

import java.util.Arrays;

/** 本地模拟工具支持的演示场景；编码兼容既有请求和历史数据。 */
public enum Scenario {
  NORMAL("normal"),
  FAIL("fail"),
  SLOW("slow"),
  /** 模拟模型反复发起相同工具调用，用于验证重复调用保护。 */
  REPEAT("repeat");

  private final String code;

  Scenario(String code) {
    this.code = code;
  }

  public String code() {
    return code;
  }

  public static Scenario fromCode(String code) {
    return Arrays.stream(values())
        .filter(value -> value.code.equals(code))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("未知模拟场景：" + code));
  }
}
