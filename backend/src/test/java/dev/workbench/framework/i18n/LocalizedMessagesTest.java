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
