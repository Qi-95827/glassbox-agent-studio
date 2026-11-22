package dev.workbench.framework.secret;

/** 生成仅供列表回显的密钥掩码；任何分支都不会返回完整密钥。 */
public final class ApiKeyMasker {
  private ApiKeyMasker() {}

  public static String mask(String apiKey) {
    if (apiKey == null || apiKey.isBlank()) return "";
    String value = apiKey.strip();
    int prefixLength = value.startsWith("sk-") ? 3 : Math.min(2, value.length());
    String prefix = value.substring(0, prefixLength);
    if (value.length() <= prefixLength + 4) return prefix + "****";
    return prefix + "****" + value.substring(value.length() - 4);
  }
}
