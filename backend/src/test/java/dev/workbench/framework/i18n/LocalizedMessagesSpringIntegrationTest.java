package dev.workbench.framework.i18n;

import static org.junit.jupiter.api.Assertions.*;

import dev.workbench.app.WorkbenchApplication;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.MessageSource;

/**
 * 在真实 Spring 上下文中验证后端文案装配。
 *
 * <p>回归重点是 {@code spring.messages.basename} 必须真正生效：一旦退回 Spring Boot 默认的空
 * MessageSource，页面就会重新收到 {@code validation.task.fileRequired} 这类裸键名。
 */
@SpringBootTest(
    classes = WorkbenchApplication.class,
    properties = {
      "spring.datasource.url=jdbc:h2:mem:i18n-messages;DB_CLOSE_DELAY=-1",
      "workbench.workspace-root=${java.io.tmpdir}/agent-workbench-i18n"
    })
class LocalizedMessagesSpringIntegrationTest {
  private static final String FILE_REQUIRED = "validation.task.fileRequired";

  @Autowired MessageSource messageSource;

  @BeforeEach
  void installApplicationMessageSource() {
    // 与 WebLocaleConfiguration 的装配方式一致：使用容器里的 MessageSource。
    LocalizedMessages.configure(messageSource);
  }

  @Test
  void resolvesChineseCopyFromApplicationMessageSource() {
    assertEquals(
        "请选择至少一个授权文件或目录。",
        LocalizedMessages.get(Locale.SIMPLIFIED_CHINESE, FILE_REQUIRED));
  }

  @Test
  void resolvesEnglishCopyFromApplicationMessageSource() {
    assertEquals(
        "Select at least one authorized file or directory.",
        LocalizedMessages.get(Locale.US, FILE_REQUIRED));
  }

  /** 裸键名是最初的线上症状；任何语言都不能把它当作自然语言返回。 */
  @Test
  void neverReturnsTheRawMessageKey() {
    for (Locale locale : new Locale[] {Locale.SIMPLIFIED_CHINESE, Locale.US, Locale.ROOT}) {
      String resolved = LocalizedMessages.get(locale, FILE_REQUIRED);
      assertNotEquals(FILE_REQUIRED, resolved, locale.toString());
      assertFalse(resolved.startsWith("validation."), locale + " => " + resolved);
    }
    assertEquals(
        "请输入 1 到 500 字的任务目标。",
        LocalizedMessages.get(Locale.SIMPLIFIED_CHINESE, "validation.task.goal"));
    assertEquals(
        "Enter a task goal of 1 to 500 characters.",
        LocalizedMessages.get(Locale.US, "validation.task.goal"));
  }

  /** 未注入任何 MessageSource 的独立单元环境同样必须解析出兜底文案。 */
  @Test
  void bundledFallbackResolvesWithoutInjectedSource() {
    try {
      LocalizedMessages.configure(null);
      assertEquals(
          "请选择至少一个授权文件或目录。",
          LocalizedMessages.get(Locale.SIMPLIFIED_CHINESE, FILE_REQUIRED));
      assertEquals(
          "Select at least one authorized file or directory.",
          LocalizedMessages.get(Locale.US, FILE_REQUIRED));
    } finally {
      LocalizedMessages.configure(messageSource);
    }
  }

  /** 新增词条必须同时写入中文与英文资源包，避免单侧缺失退化成兜底中文或裸键名。 */
  @Test
  void bundlesKeepChineseAndEnglishKeyParity() throws IOException {
    Set<String> chinese = keysOf("i18n/messages_zh_CN.properties");
    Set<String> english = keysOf("i18n/messages_en_US.properties");
    assertFalse(chinese.isEmpty());
    assertEquals(chinese, english);
  }

  private static Set<String> keysOf(String resource) throws IOException {
    var properties = new Properties();
    try (var stream = LocalizedMessagesSpringIntegrationTest.class.getClassLoader().getResourceAsStream(resource)) {
      assertNotNull(stream, resource);
      properties.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
    }
    return new LinkedHashSet<>(properties.stringPropertyNames());
  }
}
