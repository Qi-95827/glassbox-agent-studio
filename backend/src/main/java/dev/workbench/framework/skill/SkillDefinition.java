package dev.workbench.framework.skill;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** 不可变内置技能及内容摘要；内容只作为方法指导。 */
public record SkillDefinition(SkillId id, int version, String name, String guidance) {
  public SkillDefinition {
    if (id == null
        || version < 1
        || name == null
        || name.isBlank()
        || guidance == null
        || guidance.isBlank()) throw new IllegalArgumentException("技能定义不完整。");
  }

  public String contentSha256() {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(guidance.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("无法计算技能摘要。", e);
    }
  }

  public SkillRef reference() {
    return new SkillRef(id, version, contentSha256());
  }
}
