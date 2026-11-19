package dev.workbench.evaluation;

import dev.workbench.persistence.entity.EvaluationBatchEntity;
import dev.workbench.persistence.entity.EvaluationCaseEntity;
import dev.workbench.persistence.entity.EvaluationCheckEntity;
import dev.workbench.persistence.repository.EvaluationBatchRepository;
import dev.workbench.persistence.repository.EvaluationCaseRepository;
import dev.workbench.persistence.repository.EvaluationCheckRepository;
import dev.workbench.task.RunRecordService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** 评测批次和样本记录；不存储模型上下文或密钥。 */
@Service
public class EvaluationRepository {
  private final EvaluationBatchRepository batches;
  private final EvaluationCaseRepository cases;
  private final EvaluationCheckRepository checkRows;
  private final TransactionTemplate tx;
  private final com.fasterxml.jackson.databind.ObjectMapper json;

  public EvaluationRepository(
      EvaluationBatchRepository batches,
      EvaluationCaseRepository cases,
      EvaluationCheckRepository checkRows,
      TransactionTemplate tx,
      com.fasterxml.jackson.databind.ObjectMapper json) {
    this.batches = batches;
    this.cases = cases;
    this.checkRows = checkRows;
    this.tx = tx;
    this.json = json;
  }

  public void create(String id) {
    create(id, SeedSuite.Variant.LOCAL_AGENT_V2);
  }

  public void create(String id, SeedSuite.Variant suite) {
    String definition = SeedSuite.snapshot(json, suite);
    create(id, suite, null, definition, SeedSuite.sha256(definition), null, null);
  }

  public void create(
      String id,
      SeedSuite.Variant suite,
      Integer suiteVersion,
      String definition,
      String definitionSha,
      String configJson,
      String requestedBy) {
    tx.executeWithoutResult(
        ignored -> {
          String time = RunRecordService.now();
          batches.save(
              new EvaluationBatchEntity(
                  id,
                  suite.name(),
                  EvaluationStatus.RUNNING.name(),
                  time,
                  definition,
                  definitionSha,
                  suiteVersion,
                  configJson,
                  configJson == null ? null : SeedSuite.sha256(configJson),
                  requestedBy));
          int sequence = 0;
          List<EvaluationCaseEntity> rows = new ArrayList<>();
          for (SeedCase sample : suite.cases()) {
            rows.add(
                new EvaluationCaseEntity(
                    RunRecordService.id(),
                    id,
                    sample.name(),
                    ++sequence,
                    CaseStatus.PENDING.name(),
                    time));
          }
          if (!rows.isEmpty()) cases.saveAll(rows);
        });
  }

  public void startCase(String batchId, SeedCase sample) {
    tx.executeWithoutResult(
        ignored -> {
          int n =
              cases.startCase(
                  batchId,
                  sample.name(),
                  CaseStatus.PENDING.name(),
                  CaseStatus.RUNNING.name(),
                  RunRecordService.now());
          if (n != 1) throw new IllegalStateException("评测样本不能重复启动。");
        });
  }

  public void attachRun(String batchId, SeedCase sample, String runId) {
    tx.executeWithoutResult(
        ignored -> {
          int n =
              cases.attachRun(
                  batchId,
                  sample.name(),
                  CaseStatus.RUNNING.name(),
                  runId,
                  RunRecordService.now());
          if (n != 1) throw new IllegalStateException("评测样本已结束，无法关联运行。");
        });
  }

  public void finishCase(String batchId, SeedCase sample, CaseStatus status, String explanation) {
    tx.executeWithoutResult(
        ignored ->
            cases.finishCase(
                batchId,
                sample.name(),
                CaseStatus.RUNNING.name(),
                status.name(),
                explanation,
                RunRecordService.now()));
  }

  /** 案例终态和全部断言在同一事务保存，失败时不留下不完整结果。 */
  public void finishCaseWithChecks(String batchId, SeedCase sample, List<EvaluationCheck> checks) {
    if (sample == null || checks == null || checks.stream().anyMatch(java.util.Objects::isNull))
      throw new IllegalArgumentException("评测案例和断言明细不能为空。");
    var codes =
        checks.stream().map(EvaluationCheck::code).collect(java.util.stream.Collectors.toSet());
    if (codes.contains(null)
        || checks.size() != codes.size()
        || !codes.equals(sample.requiredChecks()))
      throw new IllegalArgumentException("断言明细必须包含案例要求的全部编码，不能重复或额外提供。");
    tx.executeWithoutResult(
        ignored -> {
          EvaluationCaseEntity row =
              cases
                  .findFirstByBatchIdAndCaseCodeAndStatus(
                      batchId, sample.name(), CaseStatus.RUNNING.name())
                  .orElseThrow(() -> new EmptyResultDataAccessException(1));
          int sequence = 0;
          List<EvaluationCheckEntity> rows = new ArrayList<>();
          for (var check : checks) {
            rows.add(
                new EvaluationCheckEntity(
                    row.getId(),
                    ++sequence,
                    check.code().name(),
                    check.passed(),
                    check.detail()));
          }
          // 先刷新断言，唯一/检查约束失败时在同一事务回滚，不留下不完整结果。
          if (!rows.isEmpty()) checkRows.saveAllAndFlush(rows);
          boolean passed = checks.stream().allMatch(EvaluationCheck::passed);
          int changed =
              cases.finishRunningCase(
                  row.getId(),
                  CaseStatus.RUNNING.name(),
                  passed ? CaseStatus.PASSED.name() : CaseStatus.FAILED.name(),
                  passed
                      ? "运行状态及关联证据满足固定断言。"
                      : "部分固定断言未通过，请查看断言明细及关联运行。",
                  RunRecordService.now());
          if (changed != 1) throw new IllegalStateException("评测案例状态已改变，无法保存断言。");
        });
  }

  /** 没有明细表示历史或未完成案例，不能推断各项断言通过。 */
  public List<EvaluationCheck> checks(String caseId) {
    return checkRows.findChecks(caseId).stream()
        .map(
            row ->
                new EvaluationCheck(
                    EvaluationCheckCode.valueOf(row.getId().getCheckCode()),
                    row.isPassed(),
                    row.getDetail()))
        .toList();
  }

  public void finishBatch(String id, EvaluationStatus status) {
    tx.executeWithoutResult(
        ignored -> {
          String time = RunRecordService.now();
          cases.finishAllInBatch(
              id,
              CaseStatus.PENDING.name(),
              CaseStatus.SKIPPED.name(),
              "批次提前结束，样本未执行。",
              time);
          cases.finishAllInBatch(
              id,
              CaseStatus.RUNNING.name(),
              CaseStatus.INTERRUPTED.name(),
              "批次结束时样本尚无确定结果。",
              time);
          batches.finish(
              id,
              status.name(),
              time,
              List.of(EvaluationStatus.RUNNING.name(), EvaluationStatus.CANCELLING.name()));
        });
  }

  /** 进程重启不能证明在途案例结果，保留记录且不自动重复运行。 */
  public void interruptActive() {
    for (EvaluationBatch batch : list()) {
      if (batch.status() == EvaluationStatus.RUNNING
          || batch.status() == EvaluationStatus.CANCELLING)
        finishBatch(batch.id().toString(), EvaluationStatus.INTERRUPTED);
    }
  }

  /** 保存取消请求，使界面区分请求中与已经停止。 */
  public void requestCancel(String id) {
    tx.executeWithoutResult(
        ignored -> {
          int changed =
              batches.requestCancel(
                  id, EvaluationStatus.CANCELLING.name(), EvaluationStatus.RUNNING.name());
          if (changed != 1) throw new IllegalStateException("评测已经结束或正在取消。");
        });
  }

  public List<EvaluationBatch> list() {
    return batches.findAllByOrderByCreatedAtDesc().stream()
        .map(EvaluationRepository::toBatch)
        .toList();
  }

  public EvaluationBatch get(String id) {
    return batches
        .findById(id)
        .map(EvaluationRepository::toBatch)
        .orElseThrow(() -> new EmptyResultDataAccessException(1));
  }

  public List<EvaluationCase> cases(String id) {
    List<EvaluationCaseEntity> rows = cases.findByBatchIdOrderBySequenceNoAsc(id);
    if (rows.isEmpty()) return List.of();
    List<String> caseIds = rows.stream().map(EvaluationCaseEntity::getId).toList();
    Map<String, List<EvaluationCheck>> checksByCase =
        checkRows.findChecksForCases(caseIds).stream()
            .collect(
                java.util.stream.Collectors.groupingBy(
                    row -> row.getId().getCaseId(),
                    LinkedHashMap::new,
                    java.util.stream.Collectors.mapping(
                        EvaluationRepository::toCheck, java.util.stream.Collectors.toList())));
    return rows.stream()
        .map(row -> toCase(row, checksByCase.getOrDefault(row.getId(), List.of())))
        .toList();
  }

  private static EvaluationBatch toBatch(EvaluationBatchEntity row) {
    return new EvaluationBatch(
        UUID.fromString(row.getId()),
        row.getSuite(),
        EvaluationStatus.valueOf(row.getStatus()),
        Instant.parse(row.getCreatedAt()),
        parseTime(row.getFinishedAt()),
        row.getDefinitionJson(),
        row.getDefinitionSha256(),
        row.getSuiteVersion(),
        row.getConfigJson(),
        row.getConfigSha256(),
        row.getRequestedBy());
  }

  private static EvaluationCase toCase(EvaluationCaseEntity row, List<EvaluationCheck> checks) {
    SeedCase sample = SeedCase.valueOf(row.getCaseCode());
    String run = row.getRunId();
    return new EvaluationCase(
        UUID.fromString(row.getId()),
        sample,
        sample.title(),
        CaseStatus.valueOf(row.getStatus()),
        run == null ? null : UUID.fromString(run),
        row.getExplanation(),
        Instant.parse(row.getUpdatedAt()),
        checks);
  }

  private static EvaluationCheck toCheck(EvaluationCheckEntity row) {
    return new EvaluationCheck(
        EvaluationCheckCode.valueOf(row.getId().getCheckCode()),
        row.isPassed(),
        row.getDetail());
  }

  private static Instant parseTime(String value) {
    return value == null ? null : Instant.parse(value);
  }
}
