package dev.workbench.evaluation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

/** 固定评测套件的可追溯定义；更改断言语义时必须更新断言版本。 */
public final class SeedSuite {
  public static final String CODE = "LOCAL_AGENT_V2";
  public static final int ASSERTION_VERSION = 2;
  public static final int MAX_ROUNDS = 4;
  public static final int MAX_SECONDS = 30;

  private SeedSuite() {}

  /** 快照只包含明确受控的固定参数，不代表运行环境和实现代码。 */
  public static String snapshot(ObjectMapper json) {
    var samples =
        Arrays.stream(SeedCase.values())
            .map(
                s ->
                    new Sample(
                        s.name(),
                        s.title(),
                        s.scenario(),
                        s.maxTools(),
                        s.expected().name(),
                        s.maxIdenticalTools(),
                        s.maxRounds()))
            .toList();
    try {
      return json.writeValueAsString(
          new Definition(CODE, ASSERTION_VERSION, "mock", MAX_ROUNDS, MAX_SECONDS, samples));
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("评测定义无法序列化。", e);
    }
  }

  public static String sha256(String snapshot) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(snapshot.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 不可用。", e);
    }
  }

  public record Sample(
      String code,
      String title,
      String scenario,
      int maxTools,
      String expectedStatus,
      Integer maxIdenticalTools,
      int maxRounds) {}

  public record Definition(
      String suite,
      int assertionVersion,
      String model,
      int maxRounds,
      int maxSeconds,
      List<Sample> samples) {}
}
