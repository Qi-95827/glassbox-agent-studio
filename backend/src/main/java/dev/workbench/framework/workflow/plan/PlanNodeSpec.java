package dev.workbench.framework.workflow.plan;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * 已物化候选节点的文件范围与交付契约。
 *
 * <p>摘要绑定候选摘要、节点键、读写文件、预期产出和验收条件；执行器只能使用这里的读写集合， 持久化规格被改写时运行会因摘要不一致而停止。
 */
public record PlanNodeSpec(
    UUID nodeId,
    String candidateSha256,
    String key,
    List<String> readFiles,
    List<String> writeFiles,
    List<Output> outputs,
    List<String> acceptanceCriteria,
    String sha256) {
  public enum Kind {
    TEXT,
    FILE
  }

  public record Output(Kind kind, String path, boolean required) {}

  public PlanNodeSpec {
    if (nodeId == null || key == null || !key.matches("[a-z][a-z0-9-]{0,31}"))
      throw new IllegalArgumentException("节点规格标识无效。");
    if (candidateSha256 == null || !candidateSha256.matches("[0-9a-f]{64}"))
      throw new IllegalArgumentException("节点规格候选摘要无效。");
    readFiles = List.copyOf(readFiles);
    writeFiles = List.copyOf(writeFiles);
    outputs = List.copyOf(outputs);
    acceptanceCriteria = List.copyOf(acceptanceCriteria);
    if (!readFiles.containsAll(writeFiles) || !sha256.matches("[0-9a-f]{64}"))
      throw new IllegalArgumentException("节点规格文件范围或摘要无效。");
  }

  /** 必需文件集合；用于判断节点是否真正产出了声明的交付文件。 */
  public List<String> requiredFiles() {
    return outputs.stream()
        .filter(output -> output.kind() == Kind.FILE && output.required())
        .map(Output::path)
        .toList();
  }

  /** 规格摘要必须与候选摘要绑定，避免把旧规格套用到新候选。 */
  public static String digest(
      String candidateSha256,
      String key,
      List<String> readFiles,
      List<String> writeFiles,
      List<Output> outputs,
      List<String> acceptanceCriteria) {
    var digest = new Digest().field(candidateSha256).field(key);
    digest.field(readFiles.size());
    for (String path : readFiles) digest.field(path);
    digest.field(writeFiles.size());
    for (String path : writeFiles) digest.field(path);
    digest.field(outputs.size());
    for (var output : outputs)
      digest
          .field(output.kind().name())
          .field(output.path() == null ? "" : output.path())
          .field(output.required() ? "required" : "optional");
    digest.field(acceptanceCriteria.size());
    for (String item : acceptanceCriteria) digest.field(item);
    return digest.hex();
  }

  private static final class Digest {
    private final MessageDigest digest;

    Digest() {
      try {
        digest = MessageDigest.getInstance("SHA-256");
      } catch (NoSuchAlgorithmException e) {
        throw new IllegalStateException("无法计算节点规格摘要。", e);
      }
      field("plan-node-spec-v1");
    }

    Digest field(String value) {
      byte[] bytes = (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
      digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
      digest.update(bytes);
      return this;
    }

    Digest field(int value) {
      return field(Integer.toString(value));
    }

    String hex() {
      return HexFormat.of().formatHex(digest.digest());
    }
  }
}
