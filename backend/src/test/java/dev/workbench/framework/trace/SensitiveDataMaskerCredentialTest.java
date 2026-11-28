package dev.workbench.framework.trace;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/**
 * 玻璃盒脱敏的凭据覆盖与 JSON 结构完整性。
 *
 * <p>反例对应 `F-09`：旧规则只覆盖 Bearer/sk-/含引号键值；AWS/Google/GitHub/GitLab/JWT/PEM/URL 凭据与 单引号键值全部漏网，且值匹配会吞掉
 * JSON 的 {@code }} / {@code ]}，导致落库后无法反序列化。
 */
class SensitiveDataMaskerCredentialTest {
  @Test
  void cloudAndPlatformTokensAreMasked() {
    String masked =
        SensitiveDataMasker.mask(
            "AKIAIOSFODNN7EXAMPLE AIzaSyA1234567890abcdefghijklmnopqrstuvwxyz01234 "
                + "ghp_abcdefghijklmnopqrstuvwxyz0123456789 glpat-abcdefghijklmnopqrst");

    assertFalse(masked.contains("AKIAIOSFODNN7EXAMPLE"), masked);
    assertFalse(masked.contains("AIzaSyA"), masked);
    assertFalse(masked.contains("ghp_"), masked);
    assertFalse(masked.contains("glpat-"), masked);
  }

  @Test
  void jwtPemAndUrlCredentialsAreMasked() {
    String masked =
        SensitiveDataMasker.mask(
            "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dozjgNryP4J3jVmNHl0w5N_XgL0n3I9PlFUP0THsR8U"
                + " -----BEGIN RSA PRIVATE KEY-----\n"
                + "MIIEow\n"
                + "-----END RSA PRIVATE KEY----- postgres://app:pa55word@db.internal:5432/app");

    assertFalse(masked.contains("eyJhbGci"), masked);
    assertFalse(masked.contains("MIIEow"), masked);
    assertFalse(masked.contains("pa55word"), masked);
    assertTrue(masked.contains("db.internal"), masked);
  }

  @Test
  void singleQuotedSecretKeepsJsonStructure() {
    String masked = SensitiveDataMasker.mask("{'password': 'abcdef123', 'note': 'ok'}");

    assertFalse(masked.contains("abcdef123"), masked);
    assertTrue(masked.contains("{'password':"), "键名必须保留：" + masked);
    assertTrue(masked.trim().endsWith("}"), "JSON 闭合符不能被吞：" + masked);
  }

  @Test
  void doubleQuotedDoubleBraceJsonStaysValid() {
    String masked = SensitiveDataMasker.mask("{\"api_key\": \"SecretVal123\", \"list\": [1]}");

    assertFalse(masked.contains("SecretVal123"), masked);
    assertTrue(masked.contains("\"list\": [1]}"), "数组与对象闭合符必须保留：" + masked);
  }

  @Test
  void strictMaxLengthNeverExceedsTheRequestedColumnWidth() {
    String longSource = "x".repeat(120);
    assertEquals(80, SensitiveDataMasker.maskToMaxLength(longSource, 80).length());
    assertEquals(32, SensitiveDataMasker.maskToMaxLength("y".repeat(64), 32).length());
    // 短限长不得被 maskAndTruncate 的 256 保底抬高，也不得追加任何后缀。
    assertFalse(SensitiveDataMasker.maskToMaxLength(longSource, 80).contains("已截断"));
    assertEquals(80, SensitiveDataMasker.maskToMaxLength(longSource, 80).length());

    String secret = "source=sk-live-9f8e7d6c5b4a3210-" + "z".repeat(120);
    String masked = SensitiveDataMasker.maskToMaxLength(secret, 80);
    assertTrue(masked.length() <= 80, masked);
    assertFalse(masked.contains("sk-live-9f8e7d6c5b4a3210"), masked);
    assertTrue(masked.contains("***MASKED"), masked);

    assertEquals("", SensitiveDataMasker.maskToMaxLength("", 10));
    assertNull(SensitiveDataMasker.maskToMaxLength(null, 10));
    assertThrows(
        IllegalArgumentException.class, () -> SensitiveDataMasker.maskToMaxLength("abc", 0));
  }

  @Test
  void unclosedPrivateKeyInsideSensitiveKvFieldNeverLeaksInAnyMaskEntrypoint() {
    String jsonSecretPem =
        "{\"secret\": \"-----BEGIN RSA PRIVATE KEY-----\nMIIEowIBAAKCAQEA1234567890";
    String unquotedPasswordPem = "password=-----BEGIN EC PRIVATE KEY-----\nMHcCAQEEI998877";

    for (String sample : java.util.List.of(jsonSecretPem, unquotedPasswordPem)) {
      for (String masked :
          java.util.List.of(
              SensitiveDataMasker.mask(sample),
              SensitiveDataMasker.maskCompleted(sample),
              SensitiveDataMasker.maskFinal(sample),
              SensitiveDataMasker.maskAndTruncate(sample).text())) {
        assertFalse(masked.contains("MIIEowIBAAKCAQEA1234567890"), "私钥正文泄露：" + masked);
        assertFalse(masked.contains("MHcCAQEEI998877"), "EC 私钥正文泄露：" + masked);
        assertFalse(masked.contains("PRIVATE KEY"), "私钥头不得残留：" + masked);
        assertTrue(masked.contains("***MASKED-PRIVATE-KEY***"), masked);
      }
    }
  }

  @Test
  void exactPlaceholderIsPreservedWhileMixedMaskPrefixAndPlaintextSuffixIsFullyMasked() {
    // 1. 整值恰好等于受支持的完整占位符时原样保留，不破坏已有脱敏标记与 JSON 结构。
    assertEquals(
        "{\"secret\": \"***MASKED-PRIVATE-KEY***\"}",
        SensitiveDataMasker.mask("{\"secret\": \"***MASKED-PRIVATE-KEY***\"}"));
    assertEquals(
        "{\"token\": \"***MASKED-TOKEN***\"}",
        SensitiveDataMasker.mask("{\"token\": \"***MASKED-TOKEN***\"}"));
    assertEquals(
        "{\"api_key\": \"***MASKED-KEY***\"}",
        SensitiveDataMasker.mask("{\"api_key\": \"***MASKED-KEY***\"}"));
    assertEquals("password=***MASKED***", SensitiveDataMasker.maskFinal("password=***MASKED***"));

    // 2. 专项替换后仍连着同一字段的明文后缀，或字面带掩码前缀的混合值，必须整值掩码，绝不放行后缀。
    String awsPlusSuffix = "secret=AKIAIOSFODNN7EXAMPLE:extra_secret_tail";
    String literalMaskPrefix = "password=***MASKED***_real_suffix";
    String jsonTokenPlusSuffix = "{\"token\": \"***MASKED-TOKEN***:leak999\"}";
    String fakeMaskPrefix = "password=***MASKED-FAKE_secret123";
    for (String masked :
        java.util.List.of(
            SensitiveDataMasker.mask(awsPlusSuffix),
            SensitiveDataMasker.maskCompleted(awsPlusSuffix),
            SensitiveDataMasker.maskFinal(awsPlusSuffix))) {
      assertFalse(masked.contains("AKIAIOSFODNN7EXAMPLE"), masked);
      assertFalse(masked.contains("extra_secret_tail"), "混合后缀明文泄露：" + masked);
      assertEquals("secret=***MASKED***", masked);
    }
    assertEquals("password=***MASKED***", SensitiveDataMasker.mask(literalMaskPrefix));
    assertEquals("{\"token\": \"***MASKED***\"}", SensitiveDataMasker.mask(jsonTokenPlusSuffix));
    assertEquals("password=***MASKED***", SensitiveDataMasker.mask(fakeMaskPrefix));
    // 流尾截断 KV 对短后缀同样不能豁免。
    assertEquals("password=***MASKED***", SensitiveDataMasker.maskFinal("password=***MASKED***_ab"));
  }

  @Test
  void quotedSensitiveValuesRespectUnescapedClosingQuoteAcrossSpacesCommasAndEscapes() {
    // 1. 同一双引号敏感字段内含空格、逗号或转义引号的混合值：明文后缀必须彻底消失，相邻 JSON 字段必须保留。
    String commaMix =
        "{\"password\": \"AKIAIOSFODNN7EXAMPLE, extra secret\", \"note\": \"keep-me\"}";
    String spaceMix =
        "{\"secret\": \"***MASKED-TOKEN*** extra plain tail\", \"note\": \"keep-me\"}";
    String escapedQuoteMix =
        "{\"password\": \"***MASKED***, \\\"quoted\\\" tail\", \"note\": \"keep-me\"}";
    String escapedBackslashEnd =
        "{\"password\": \"secret\\\\\", \"note\": \"keep-me\"}";
    String singleQuotedMix =
        "{'password': '***MASKED-KEY***, extra secret', 'note': 'keep-me'}";

    for (String sample : java.util.List.of(commaMix, spaceMix, escapedQuoteMix, escapedBackslashEnd)) {
      for (String masked :
          java.util.List.of(
              SensitiveDataMasker.mask(sample),
              SensitiveDataMasker.maskCompleted(sample),
              SensitiveDataMasker.maskFinal(sample))) {
        assertFalse(masked.contains("extra secret"), masked);
        assertFalse(masked.contains("extra plain tail"), masked);
        assertFalse(masked.contains("quoted"), masked);
        assertFalse(masked.contains("AKIAIOSFODNN7EXAMPLE"), masked);
        assertTrue(masked.contains("\"note\": \"keep-me\"}"), "相邻 JSON 字段必须完整保留：" + masked);
      }
    }
    assertEquals(
        "{\"password\": \"***MASKED***\", \"note\": \"keep-me\"}",
        SensitiveDataMasker.mask(commaMix));
    assertEquals(
        "{\"secret\": \"***MASKED***\", \"note\": \"keep-me\"}",
        SensitiveDataMasker.mask(spaceMix));
    assertEquals(
        "{\"password\": \"***MASKED***\", \"note\": \"keep-me\"}",
        SensitiveDataMasker.mask(escapedQuoteMix));
    assertEquals(
        "{\"password\": \"***MASKED***\", \"note\": \"keep-me\"}",
        SensitiveDataMasker.mask(escapedBackslashEnd));
    assertEquals(
        "{'password': '***MASKED***', 'note': 'keep-me'}",
        SensitiveDataMasker.mask(singleQuotedMix));

    // 2. 流尾未闭合引号内含逗号/空格后缀的截断收尾，以及短普通引号字段不跨引号误吞相邻字段。
    assertEquals(
        "{\"password\": \"***MASKED***",
        SensitiveDataMasker.maskFinal("{\"password\": \"***MASKED***, tail"));
    assertEquals(
        "{'password': '***MASKED***",
        SensitiveDataMasker.maskFinal("{'password': '***MASKED*** tail"));
    assertEquals(
        "{\"token\": \"100\", \"note\": \"hello world\"}",
        SensitiveDataMasker.mask("{\"token\": \"100\", \"note\": \"hello world\"}"));
  }

  @Test
  void ordinaryTextIsUnchanged() {
    String text = "排序页面需要保留原生实现并给出证据。";

    assertEquals(text, SensitiveDataMasker.mask(text));
  }
}
