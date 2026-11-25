package dev.workbench.report;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/**
 * HTML 报告脱敏：JSON 形态的密钥必须脱敏，且不得破坏 HTML 结构。
 *
 * <p>反例对应 `F-09`：脱敏发生在 HTML 转义之后，JSON 的引号已变成 {@code &quot;}，旧规则 100% 漏网； 同时路径规则不能吞掉 {@code
 * </pre></section>} 这类闭合标签。
 */
class HtmlReportRedactionTest {
  @Test
  void jsonSecretInsideEscapedHtmlIsRedacted() {
    String html =
        "<section><pre>{\n  &quot;api_key&quot;: &quot;SecretVal123&quot;\n}</pre></section>";

    String redacted = HtmlReportService.redact(html);

    assertFalse(redacted.contains("SecretVal123"), "JSON 形态密钥必须脱敏：" + redacted);
    assertTrue(redacted.contains("&quot;api_key&quot;"), "键名保留便于定位：" + redacted);
    assertTrue(redacted.contains("</pre></section>"), "闭合标签必须完整保留：" + redacted);
  }

  @Test
  void rawJsonSecretIsRedactedToo() {
    String redacted = HtmlReportService.redact("{'api_key': 'RawSecret9', 'token': 'TokValue8'}");

    assertFalse(redacted.contains("RawSecret9"), redacted);
    assertFalse(redacted.contains("TokValue8"), redacted);
    assertTrue(redacted.trim().endsWith("}"), "JSON 闭合符不能被吞：" + redacted);
  }

  @Test
  void htmlStructureSurvivesPathAndEmailRedaction() {
    String html =
        "<section><pre>路径 D:\\work\\secret.txt 邮箱 dev@example.com 文档"
            + " https://example.com/a/b.md</pre></section>";

    String redacted = HtmlReportService.redact(html);

    assertTrue(redacted.contains("<section><pre>"), redacted);
    assertTrue(redacted.contains("</pre></section>"), redacted);
    assertTrue(redacted.contains("https://example.com/a/b.md"), "URL 不能被当作盘符路径吞掉：" + redacted);
    assertFalse(redacted.contains("D:\\work"), redacted);
    assertFalse(redacted.contains("dev@example.com"), redacted);
  }
}
