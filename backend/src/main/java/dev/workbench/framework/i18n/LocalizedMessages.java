package dev.workbench.framework.i18n;

import java.util.Locale;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.support.ResourceBundleMessageSource;

/**
 * 轻量本地化文案入口。
 *
 * <p>业务代码只保存 messageKey 与结构化参数，具体自然语言来自 {@code i18n/messages*.properties}。
 *
 * <p>装配层注入的 MessageSource 优先；注入源无法解析（例如 Spring Boot 只注册了空的
 * DelegatingMessageSource）或未注入时，回退到内置的 {@code i18n/messages} 资源包，保证任何路径都不会
 * 把 messageKey 当作自然语言返回给页面。两者都缺失该 key 时才回退为 key 本身。
 */
public final class LocalizedMessages {
  private static final String BASENAME = "i18n/messages";
  private static final MessageSource FALLBACK = fallbackSource();

  private static volatile MessageSource source;

  private LocalizedMessages() {}

  /** 由应用装配层注入；重复调用以最后一次为准，便于测试替换。 */
  public static void configure(MessageSource messageSource) {
    source = messageSource;
  }

  public static String get(String key, Object... args) {
    return get(LocaleContextHolder.getLocale(), key, args);
  }

  public static String get(Locale locale, String key, Object... args) {
    Locale target = locale == null ? Locale.SIMPLIFIED_CHINESE : locale;
    MessageSource current = source;
    if (current != null) {
      String resolved = resolve(current, target, key, args);
      if (resolved != null) return resolved;
    }
    String resolved = resolve(FALLBACK, target, key, args);
    return resolved == null ? key : resolved;
  }

  /** 缺少 key 时使用调用方提供的兜底文案。 */
  public static String getOrDefault(String key, String fallback, Object... args) {
    String resolved = get(LocaleContextHolder.getLocale(), key, args);
    return key.equals(resolved) ? fallback : resolved;
  }

  public static String detectLocaleCode() {
    Locale locale = LocaleContextHolder.getLocale();
    return locale != null && locale.getLanguage().equalsIgnoreCase("en") ? "en-US" : "zh-CN";
  }

  /** 解析失败（缺 key 或源不可用）返回 null，交由调用方决定回退顺序。 */
  private static String resolve(MessageSource messageSource, Locale locale, String key, Object... args) {
    if (key == null || key.isBlank()) return null;
    try {
      return messageSource.getMessage(key, args, locale);
    } catch (RuntimeException e) {
      return null;
    }
  }

  private static ResourceBundleMessageSource fallbackSource() {
    var bundle = new ResourceBundleMessageSource();
    bundle.setBasenames(BASENAME);
    bundle.setDefaultEncoding("UTF-8");
    bundle.setFallbackToSystemLocale(false);
    bundle.setUseCodeAsDefaultMessage(false);
    return bundle;
  }
}
