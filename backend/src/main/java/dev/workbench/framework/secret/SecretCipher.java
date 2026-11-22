package dev.workbench.framework.secret;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 服务端对称加密工具：AES-256-GCM，随机 12 字节 IV。
 *
 * <p>主密钥优先读取环境变量 {@code AGENT_WORKBENCH_SECRET_KEY}，否则读取 {@code config/master.key}；
 * 文件不存在时首次运行自动生成并写入 32 字节随机密钥。密文格式为 Base64(IV || 密文 || 认证标签)，
 * 只用于服务端落库，任何日志和接口响应都不得输出明文或主密钥。
 */
public final class SecretCipher {
  private static final String TRANSFORMATION = "AES/GCM/NoPadding";
  private static final int IV_BYTES = 12;
  private static final int TAG_BITS = 128;
  private static final int KEY_BYTES = 32;
  private static final String ENV_KEY = "AGENT_WORKBENCH_SECRET_KEY";
  private static final Path KEY_FILE = Path.of("config", "master.key");
  private static final SecureRandom RANDOM = new SecureRandom();
  private static volatile SecretCipher shared;

  private final SecretKeySpec key;

  public SecretCipher(byte[] keyBytes) {
    if (keyBytes == null || keyBytes.length != KEY_BYTES)
      throw new IllegalArgumentException("主密钥必须是 32 字节。");
    this.key = new SecretKeySpec(keyBytes.clone(), "AES");
  }

  /** 全局共享实例；首次使用时才读取或生成主密钥，不在启动时输出密钥内容。 */
  public static SecretCipher shared() {
    SecretCipher current = shared;
    if (current == null) {
      synchronized (SecretCipher.class) {
        current = shared;
        if (current == null) {
          current = new SecretCipher(loadOrCreateKey());
          shared = current;
        }
      }
    }
    return current;
  }

  public String encrypt(String plainText) {
    if (plainText == null) throw new IllegalArgumentException("待加密内容不能为空。");
    try {
      byte[] iv = new byte[IV_BYTES];
      RANDOM.nextBytes(iv);
      Cipher cipher = Cipher.getInstance(TRANSFORMATION);
      cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
      byte[] encrypted = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
      byte[] payload = new byte[iv.length + encrypted.length];
      System.arraycopy(iv, 0, payload, 0, iv.length);
      System.arraycopy(encrypted, 0, payload, iv.length, encrypted.length);
      return Base64.getEncoder().encodeToString(payload);
    } catch (Exception e) {
      throw new IllegalStateException("无法加密凭据。", e);
    }
  }

  public String decrypt(String encoded) {
    if (encoded == null || encoded.isBlank())
      throw new IllegalArgumentException("密文不能为空。");
    try {
      byte[] payload = Base64.getDecoder().decode(encoded);
      if (payload.length <= IV_BYTES) throw new IllegalArgumentException("密文格式无效。");
      Cipher cipher = Cipher.getInstance(TRANSFORMATION);
      cipher.init(
          Cipher.DECRYPT_MODE,
          key,
          new GCMParameterSpec(TAG_BITS, payload, 0, IV_BYTES));
      byte[] plain = cipher.doFinal(payload, IV_BYTES, payload.length - IV_BYTES);
      return new String(plain, StandardCharsets.UTF_8);
    } catch (IllegalArgumentException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException("无法解密凭据。", e);
    }
  }

  private static byte[] loadOrCreateKey() {
    String env = System.getenv(ENV_KEY);
    if (env != null && !env.isBlank()) return decodeKey(env.strip());
    try {
      if (Files.exists(KEY_FILE)) return decodeKey(Files.readString(KEY_FILE).strip());
      byte[] generated = new byte[KEY_BYTES];
      RANDOM.nextBytes(generated);
      Files.createDirectories(KEY_FILE.getParent());
      Files.writeString(KEY_FILE, Base64.getEncoder().encodeToString(generated));
      return generated;
    } catch (java.io.IOException e) {
      throw new IllegalStateException("无法读取或生成主密钥文件 config/master.key。", e);
    }
  }

  /** 支持 32 字节 Base64 主密钥；其他字符串按 SHA-256 派生，便于直接配置口令。 */
  private static byte[] decodeKey(String raw) {
    try {
      byte[] decoded = Base64.getDecoder().decode(raw);
      if (decoded.length == KEY_BYTES) return decoded;
    } catch (IllegalArgumentException ignored) {
      // 非 Base64 时按口令派生。
    }
    try {
      return MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
    } catch (Exception e) {
      throw new IllegalStateException("无法解析主密钥。", e);
    }
  }
}
