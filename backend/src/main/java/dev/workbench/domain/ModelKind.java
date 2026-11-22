package dev.workbench.domain;

/** 一次模型调用的实际来源。 */
public enum ModelKind {
  MOCK("mock"),
  DEEPSEEK_FLASH("deepseek-flash"),
  /** 用户自带 Key 的模型连接；费用由用户自己的服务商结算。 */
  USER_CONNECTION("user-connection");

  private final String code;

  ModelKind(String code) {
    this.code = code;
  }

  public String code() {
    return code;
  }

  public static ModelKind forMode(ModelMode mode) {
    return switch (mode) {
      case MOCK -> MOCK;
      case DEEPSEEK -> DEEPSEEK_FLASH;
      case USER -> USER_CONNECTION;
    };
  }
}
