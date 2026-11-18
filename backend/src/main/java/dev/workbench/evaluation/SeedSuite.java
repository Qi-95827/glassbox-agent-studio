package dev.workbench.evaluation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/** 固定评测套件的可追溯定义；更改断言语义时必须更新断言版本。 */
public final class SeedSuite {
  public static final String CODE = "LOCAL_AGENT_V2";
  public static final int ASSERTION_VERSION = 2;
  public static final int MAX_ROUNDS = 4;
  public static final int MAX_SECONDS = 30;

  private SeedSuite() {}

  /** 每条已发布套件保留自己的案例清单与快照格式。 */
  public enum Variant {
    LOCAL_AGENT_V2(
        List.of(
            SeedCase.NORMAL, SeedCase.TOOL_FAILURE, SeedCase.TOOL_LIMIT, SeedCase.REPEAT_LIMIT)),
    DYNAMIC_WORKFLOW_V1(List.of(SeedCase.DYNAMIC_DUAL_ANALYSIS, SeedCase.DYNAMIC_SHARED_BUDGET)),
    /** 检查节点与安全阻塞反例；使用临时只读工作区，不调用真实模型或 Docker。 */
    DYNAMIC_CHECK_V1(List.of(SeedCase.DYNAMIC_CHECK_PASS, SeedCase.DYNAMIC_CHECK_BLOCKED));

    private final List<SeedCase> cases;

    Variant(List<SeedCase> cases) {
      this.cases = cases;
    }

    public List<SeedCase> cases() {
      return cases;
    }
  }

  /** 快照只包含明确受控的固定参数，不代表运行环境和实现代码。 */
  public static String snapshot(ObjectMapper json, Variant suite) {
    try {
      if (suite == Variant.DYNAMIC_WORKFLOW_V1 || suite == Variant.DYNAMIC_CHECK_V1) {
        var dynamicSamples =
            suite.cases().stream()
                .map(
                    sample ->
                        new DynamicSample(
                            sample.name(),
                            sample.title(),
                            sample.scenario(),
                            sample.workflowTemplate(),
                            sample.templateVersion(),
                            sample.maxRounds(),
                            sample.maxTools(),
                            sample.expected().name(),
                            sample.requiredChecks().stream().map(Enum::name).sorted().toList()))
                .toList();
        return json.writeValueAsString(
            new DynamicDefinition(suite.name(), 1, "mock", MAX_SECONDS, dynamicSamples));
      }
      var samples =
          suite.cases().stream()
              .map(
                  sample ->
                      new Sample(
                          sample.name(),
                          sample.title(),
                          sample.scenario(),
                          sample.maxTools(),
                          sample.expected().name(),
                          sample.maxIdenticalTools(),
                          sample.maxRounds()))
              .toList();
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

  private record DynamicSample(
      String code,
      String title,
      String scenario,
      String template,
      int templateVersion,
      int maxRounds,
      int maxTools,
      String expectedStatus,
      List<String> checks) {}

  private record DynamicDefinition(
      String suite,
      int assertionVersion,
      String model,
      int maxSeconds,
      List<DynamicSample> samples) {}
}
