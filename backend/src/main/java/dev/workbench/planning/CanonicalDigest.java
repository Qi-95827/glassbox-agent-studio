package dev.workbench.planning;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 候选计划与输入快照的规范摘要。
 *
 * <p>每个字段先写 UTF-8 字节长度再写内容，避免相邻字段拼接产生歧义；编码版本随算法一起写在 首字段，协议变化时必须显式升级，不能继续用同一摘要比较新旧结构。
 */
final class CanonicalDigest {
  private final MessageDigest digest;

  CanonicalDigest(String encodingVersion) {
    try {
      digest = MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("无法计算计划摘要。", e);
    }
    field(encodingVersion);
  }

  CanonicalDigest field(String value) {
    byte[] bytes = (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
    digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
    digest.update(bytes);
    return this;
  }

  CanonicalDigest field(int value) {
    return field(Integer.toString(value));
  }

  String hex() {
    return HexFormat.of().formatHex(digest.digest());
  }
}
