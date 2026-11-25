package dev.workbench.report;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/**
 * 报告脱敏规则覆盖度与误伤。
 *
 * <p>反例对应 `F-09`：旧规则只有 OpenAI/GitHub 与含引号的键值对，漏掉 AWS/Google/GitLab/JWT/PEM/URL 凭据 与单引号键值；同时 Windows
 * 盘符规则会把 {@code https://host/a/b} 整段吞掉。
 */
class SensitiveTextRedactorTest {
  @Test
  void urlSchemeIsNotMistakenForWindowsDrive() {
    String redacted = SensitiveTextRedactor.redact("下载地址 https://example.com/docs/readme.md 可访问");

    assertTrue(
        redacted.contains("https://example.com/docs/readme.md"), "URL 不能被当作盘符路径脱敏：" + redacted);
  }

  @Test
  void windowsPathsAreStillRedacted() {
    String redacted = SensitiveTextRedactor.redact("读取 D:\\work\\secret\\notes.txt 失败");

    assertFalse(redacted.contains("D:\\work"), redacted);
    assertTrue(redacted.contains("[已脱敏路径]"));
  }

  @Test
  void cloudAndPlatformTokensAreMasked() {
    String text =
        String.join(
            " ",
            "AWS AKIAIOSFODNN7EXAMPLE",
            "Google AIzaSyA1234567890abcdefghijklmnopqrstuvwxyz01234",
            "GitHub ghp_abcdefghijklmnopqrstuvwxyz0123456789",
            "GitLab glpat-abcdefghijklmnopqrst",
            "OpenAI sk-abcdefghijklmnop");

    String redacted = SensitiveTextRedactor.redact(text);

    for (String secret :
        new String[] {
          "AKIAIOSFODNN7EXAMPLE",
          "AIzaSyA1234567890abcdefghijklmnopqrstuvwxyz01234",
          "ghp_",
          "glpat-",
          "sk-"
        }) assertFalse(redacted.contains(secret), "必须屏蔽 " + secret + "：" + redacted);
    assertTrue(redacted.contains("[已脱敏密钥]"), redacted);
  }

  @Test
  void jwtPemAndUrlCredentialsAreMasked() {
    String text =
        "token=eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dozjgNryP4J3jVmNHl0w5N_XgL0n3I9PlFUP0THsR8U"
            + " -----BEGIN RSA PRIVATE KEY-----\n"
            + "MIIEow\n"
            + "-----END RSA PRIVATE KEY----- 数据库 postgres://user:pa55word@db.internal:5432/app 已连接";

    String redacted = SensitiveTextRedactor.redact(text);

    assertFalse(redacted.contains("eyJhbGci"), redacted);
    assertFalse(redacted.contains("MIIEow"), "PEM 私钥正文必须整段屏蔽：" + redacted);
    assertFalse(redacted.contains("pa55word"), "URL 内嵌密码必须屏蔽：" + redacted);
    assertTrue(redacted.contains("db.internal"), "主机名保留，便于定位：" + redacted);
  }

  @Test
  void singleQuotedKeysAreRedacted() {
    String redacted = SensitiveTextRedactor.redact("{'api_key': 'abcdef123456', 'note': 'ok'}");

    assertFalse(redacted.contains("abcdef123456"), redacted);
  }

  @Test
  void ordinaryTextAndEmailsAreHandled() {
    String redacted = SensitiveTextRedactor.redact("联系人 dev@example.com 负责排序功能");

    assertFalse(redacted.contains("dev@example.com"));
    assertTrue(redacted.contains("排序功能"), "正常正文必须保留：" + redacted);
  }

  @Test
  void emptyOrNullInputIsSafe() {
    assertEquals("", SensitiveTextRedactor.redact(null));
    assertEquals("", SensitiveTextRedactor.redact(""));
    assertEquals(13, SensitiveTextRedactor.ruleNames().size());
  }
}
