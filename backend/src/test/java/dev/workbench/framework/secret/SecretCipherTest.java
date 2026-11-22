package dev.workbench.framework.secret;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import org.junit.jupiter.api.Test;

class SecretCipherTest {
  private static SecretCipher cipher() {
    byte[] key = new byte[32];
    new SecureRandom(new byte[] {1, 2, 3}).nextBytes(key);
    return new SecretCipher(key);
  }

  @Test
  void roundTripsAndUsesRandomIv() {
    SecretCipher cipher = cipher();
    String plain = "sk-secret-value-123456";
    String first = cipher.encrypt(plain);
    String second = cipher.encrypt(plain);
    assertNotEquals(first, second, "每次加密必须使用新的随机 IV");
    assertFalse(first.contains(plain));
    assertEquals(plain, cipher.decrypt(first));
    assertEquals(plain, cipher.decrypt(second));
  }

  @Test
  void rejectsTamperedCiphertext() {
    SecretCipher cipher = cipher();
    byte[] payload =
        java.util.Base64.getDecoder().decode(cipher.encrypt("sk-hidden"));
    payload[payload.length - 1] ^= 0x01;
    String tampered = java.util.Base64.getEncoder().encodeToString(payload);
    assertThrows(IllegalStateException.class, () -> cipher.decrypt(tampered));
  }

  @Test
  void maskNeverLeaksFullKey() {
    assertEquals("sk-****3f9a", ApiKeyMasker.mask("sk-12345678903f9a"));
    assertEquals("sk-****", ApiKeyMasker.mask("sk-12"));
    assertEquals("", ApiKeyMasker.mask(null));
    String masked = ApiKeyMasker.mask("plain-key-value");
    assertFalse(masked.contains("plain-key-value"));
  }

  @Test
  void encryptedPayloadIsBase64OfIvAndCiphertext() {
    String encrypted = cipher().encrypt("值");
    assertTrue(encrypted.chars().allMatch(ch -> Character.isLetterOrDigit(ch) || ch == '+' || ch == '/' || ch == '='));
    assertTrue(
        new String(java.util.Base64.getDecoder().decode(encrypted), StandardCharsets.ISO_8859_1)
            .length()
            > 12);
  }
}
