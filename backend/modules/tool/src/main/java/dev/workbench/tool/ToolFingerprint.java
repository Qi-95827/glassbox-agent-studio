package dev.workbench.tool;

import dev.workbench.domain.ToolKind;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** 比较工具输入的摘要；不保存参数正文，摘要本身不等于加密。 */
public final class ToolFingerprint {
  private ToolFingerprint() {}

  public static String of(ToolKind kind, String... parts) {
    try {
      var digest = MessageDigest.getInstance("SHA-256");
      update(digest, kind.name());
      for (String part : parts) update(digest, part);
      return HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("无法计算工具调用摘要。", e);
    }
  }

  private static void update(MessageDigest digest, String part) {
    byte[] bytes = part == null ? new byte[0] : part.getBytes(StandardCharsets.UTF_8);
    digest.update(ByteBuffer.allocate(4).putInt(part == null ? -1 : bytes.length).array());
    digest.update(bytes);
  }
}
