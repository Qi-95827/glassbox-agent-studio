package dev.workbench.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.workbench.audit.AuditService;
import dev.workbench.persistence.entity.EvaluationSuiteVersionEntity;
import dev.workbench.persistence.repository.EvaluationSuiteVersionRepository;
import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedCaseInsensitiveMap;

/** 不可变评测套件版本；发布后保留定义快照，批次可引用具体版本。 */
@Service
public class EvaluationSuiteService {
  private final EvaluationSuiteVersionRepository versions;
  private final ObjectMapper json;
  private final AuditService audit;

  public EvaluationSuiteService(
      EvaluationSuiteVersionRepository versions, ObjectMapper json, AuditService audit) {
    this.versions = versions;
    this.json = json;
    this.audit = audit;
  }

  @PostConstruct
  void ensurePublished() {
    for (SeedSuite.Variant variant : SeedSuite.Variant.values())
      if (latest(variant) == null) publish(variant, "system-seed");
  }

  public List<Map<String, Object>> list() {
    return versions.findAllByOrderBySuiteAscVersionDesc().stream()
        .map(
            row -> {
              Map<String, Object> item = new LinkedCaseInsensitiveMap<>();
              item.put("ID", row.getId());
              item.put("SUITE", row.getSuite());
              item.put("VERSION", row.getVersion());
              item.put("DEFINITION_SHA256", row.getDefinitionSha256());
              item.put("ACTIVE", row.isActive());
              item.put("CREATED_AT", row.getCreatedAt());
              return item;
            })
        .toList();
  }

  public Version publish(String suiteText, String requestedBy) {
    SeedSuite.Variant suite = parse(suiteText);
    return publish(suite, requestedBy);
  }

  public synchronized Version publish(SeedSuite.Variant suite, String requestedBy) {
    Version latest = latest(suite);
    int version = latest == null ? 1 : latest.version() + 1;
    String definition = SeedSuite.snapshot(json, suite);
    String sha = SeedSuite.sha256(definition);
    String id = dev.workbench.domain.WorkbenchIds.newId();
    versions.saveAndFlush(
        new EvaluationSuiteVersionEntity(
            id, suite.name(), version, definition, sha, true, dev.workbench.domain.WorkbenchTime.now()));
    audit.record(
        "EVALUATION_SUITE_PUBLISH",
        "EVALUATION_SUITE",
        suite.name() + "@" + version,
        "PUBLISHED",
        "操作者 " + (requestedBy == null ? AuditService.LOCAL_ACTOR : requestedBy) + "；摘要 " + sha);
    return new Version(id, suite.name(), version, definition, sha, Instant.now().toString());
  }

  /** 指定版本必须存在；未指定时使用该套件最新版本。 */
  public Version resolve(SeedSuite.Variant suite, Integer version) {
    Optional<EvaluationSuiteVersionEntity> row =
        version == null
            ? versions.findFirstBySuiteOrderByVersionDesc(suite.name())
            : versions.findFirstBySuiteAndVersion(suite.name(), version);
    return row.map(EvaluationSuiteService::toVersion)
        .orElseThrow(() -> new IllegalArgumentException("评测套件版本不存在，请先发布该版本。"));
  }

  private Version latest(SeedSuite.Variant suite) {
    return versions
        .findFirstBySuiteOrderByVersionDesc(suite.name())
        .map(EvaluationSuiteService::toVersion)
        .orElse(null);
  }

  private static Version toVersion(EvaluationSuiteVersionEntity row) {
    return new Version(
        row.getId(),
        row.getSuite(),
        row.getVersion(),
        row.getDefinitionJson(),
        row.getDefinitionSha256(),
        row.getCreatedAt());
  }

  private static SeedSuite.Variant parse(String value) {
    try {
      return SeedSuite.Variant.valueOf(value);
    } catch (RuntimeException e) {
      throw new IllegalArgumentException("未知评测套件。");
    }
  }

  public record Version(
      String id,
      String suite,
      int version,
      String definitionJson,
      String definitionSha256,
      String createdAt) {}
}
