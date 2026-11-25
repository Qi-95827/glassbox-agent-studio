package dev.workbench.framework.i18n;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Locale;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.support.ResourceBundleMessageSource;

/** 验证中文/英文 MessageSource 解析与缺失 key 的优雅回退。 */
class LocalizedMessagesTest {

  @AfterEach
  void resetLocale() {
    LocaleContextHolder.resetLocaleContext();
  }

  @Test
  void resolvesChineseAndEnglishMessages() {
    var bundle = new ResourceBundleMessageSource();
    bundle.setBasenames("i18n/messages");
    bundle.setDefaultEncoding("UTF-8");
    LocalizedMessages.configure(bundle);

    assertTrue(LocalizedMessages.get(Locale.US, "probe.auth").contains("Invalid or expired"));
    assertTrue(LocalizedMessages.get(Locale.SIMPLIFIED_CHINESE, "probe.auth").contains("密钥无效"));
    assertEquals("missing.key", LocalizedMessages.get(Locale.US, "missing.key"));
  }

  /** 任务校验文案必须按 Accept-Language 解析成自然语言，而不是回退成裸键名。 */
  @Test
  void resolvesTaskValidationCopyForBothLanguages() {
    var bundle = new ResourceBundleMessageSource();
    bundle.setBasenames("i18n/messages");
    bundle.setDefaultEncoding("UTF-8");
    LocalizedMessages.configure(bundle);

    assertEquals(
        "请选择至少一个授权文件或目录。",
        LocalizedMessages.get(Locale.SIMPLIFIED_CHINESE, "validation.task.fileRequired"));
    assertEquals(
        "Select at least one authorized file or directory.",
        LocalizedMessages.get(Locale.US, "validation.task.fileRequired"));
  }

  /** 装配层注入空 MessageSource 时仍必须回退到内置资源包，不能把 key 交给页面。 */
  @Test
  void fallsBackToBundledMessagesWhenInjectedSourceIsEmpty() {
    LocalizedMessages.configure(new org.springframework.context.support.DelegatingMessageSource());
    try {
      assertEquals(
          "请选择至少一个授权文件或目录。",
          LocalizedMessages.get(Locale.SIMPLIFIED_CHINESE, "validation.task.fileRequired"));
      assertEquals(
          "Select at least one authorized file or directory.",
          LocalizedMessages.get(Locale.US, "validation.task.fileRequired"));
    } finally {
      LocalizedMessages.configure(null);
    }
  }

  @Test
  void defaultsToChineseWhenNoMessageSourceConfigured() {
    var bundle = new ResourceBundleMessageSource();
    bundle.setBasenames("i18n/messages");
    bundle.setDefaultEncoding("UTF-8");
    LocalizedMessages.configure(bundle);
    LocaleContextHolder.setLocale(Locale.SIMPLIFIED_CHINESE);
    assertEquals("zh-CN", LocalizedMessages.detectLocaleCode());
    LocaleContextHolder.setLocale(Locale.US);
    assertEquals("en-US", LocalizedMessages.detectLocaleCode());
  }
}
