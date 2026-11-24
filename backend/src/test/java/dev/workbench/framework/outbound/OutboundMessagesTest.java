package dev.workbench.framework.outbound;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.ResourceBundleMessageSource;

/**
 * 新增的出站与 MCP 安全文案必须同时存在于中文、英文两套资源包。
 *
 * <p>这些文案会直接展示在被拦截的配置错误里；缺一条就会让前端看到裸 key，导致用户无法判断 是哪一项地址或环境变量被拒绝。这里直接读取资源包，不依赖 {@code
 * LocalizedMessages} 的全局状态。
 */
class OutboundMessagesTest {
  private static final List<String> KEYS =
      List.of(
          "outbound.url.required",
          "outbound.url.invalid",
          "outbound.url.credentials",
          "outbound.url.query",
          "outbound.url.fragment",
          "outbound.url.scheme",
          "outbound.url.host",
          "outbound.url.loopback",
          "outbound.url.private",
          "outbound.url.unresolved",
          "outbound.url.loopbackHttp",
          "outbound.mcp.stdioAdmin",
          "outbound.mcp.env",
          "outbound.mcp.args",
          "outbound.mcp.header",
          "modelConnection.baseUrlQuery",
          "modelConnection.baseUrlLoopbackDisabled",
          "modelConnection.baseUrlUnresolved");

  @Test
  void everyGuardMessageExistsInBothLocales() {
    var bundle = new ResourceBundleMessageSource();
    bundle.setBasenames("i18n/messages");
    bundle.setDefaultEncoding("UTF-8");
    bundle.setUseCodeAsDefaultMessage(false);
    for (String key : KEYS) {
      String chinese = bundle.getMessage(key, null, Locale.SIMPLIFIED_CHINESE);
      String english = bundle.getMessage(key, null, Locale.US);
      assertNotNull(chinese, key);
      assertNotNull(english, key);
      assertFalse(chinese.isBlank(), key);
      assertFalse(english.isBlank(), key);
      assertNotEquals(chinese, english, "中英文文案不能相同，需分别维护：" + key);
    }
  }
}
