package dev.workbench.store;

/** 存储层共享的标识、时间和摘要工具；不承载业务职责。 */
final class RecordSupport {
  private RecordSupport() {}

  static String id() {
    return dev.workbench.domain.WorkbenchIds.newId();
  }

  static String now() {
    return dev.workbench.domain.WorkbenchTime.now();
  }

  static String checksum(String... parts) {
    try {
      var digest = java.security.MessageDigest.getInstance("SHA-256");
      for (String part : parts) {
        byte[] bytes =
            (part == null ? "" : part).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        digest.update(java.nio.ByteBuffer.allocate(4).putInt(part == null ? -1 : bytes.length).array());
        digest.update(bytes);
      }
      return java.util.HexFormat.of().formatHex(digest.digest());
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
