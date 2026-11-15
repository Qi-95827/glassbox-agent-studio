package dev.workbench.domain;

/** 一次模型调用的实际来源。 */
public enum ModelKind {
  MOCK("mock"),
  DEEPSEEK_FLASH("deepseek-flash");

  private final String code;

  ModelKind(String code) {
    this.code = code;
  }

  public String code() {
    return code;
  }
}
