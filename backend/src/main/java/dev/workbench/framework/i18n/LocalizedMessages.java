package dev.workbench.framework.i18n;

import java.util.Locale;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.support.ResourceBundleMessageSource;

/**
 * 轻量本地化文案入口。
 *
 * <p>业务代码只保存 messageKey 与结构化参数，具体自然语言来自 {@code i18n/messages*.properties}。
 * 未配置 Spring MessageSource 的独立单元测试环境会优雅回退为 key 本身，不抛异常。
 */
public final class LocalizedMessages {
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
    MessageSource current = source;
    if (current == null) current = fallbackSource();
    try {
      return current.getMessage(
          key, args, locale == null ? Locale.SIMPLIFIED_CHINESE : locale);
    } catch (RuntimeException e) {
      return key;
    }
  }

  /** 缺少 key 时使用调用方提供的兜底文案。 */
  public static String getOrDefault(String key, String fallback, Object... args) {
    MessageSource current = source == null ? fallbackSource() : source;
    try {
      return current.getMessage(key, args, LocaleContextHolder.getLocale());
    } catch (RuntimeException e) {
      return fallback;
    }
  }

  public static String detectLocaleCode() {
    Locale locale = LocaleContextHolder.getLocale();
    return locale != null && locale.getLanguage().equalsIgnoreCase("en") ? "en-US" : "zh-CN";
  }

  private static MessageSource fallbackSource() {
    var bundle = new ResourceBundleMessageSource();
    bundle.setBasenames("i18n/messages");
    bundle.setDefaultEncoding("UTF-8");
    bundle.setUseCodeAsDefaultMessage(true);
    return bundle;
  }
}
