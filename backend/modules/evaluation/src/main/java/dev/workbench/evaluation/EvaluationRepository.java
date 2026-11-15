package dev.workbench.evaluation;

import dev.workbench.task.RunRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

/** 评测批次和样本记录；不存储模型上下文或密钥。 */
@Repository
public class EvaluationRepository {
  private final JdbcTemplate db;
  private final TransactionTemplate tx;
  private final com.fasterxml.jackson.databind.ObjectMapper json;

  public EvaluationRepository(
      JdbcTemplate db, TransactionTemplate tx, com.fasterxml.jackson.databind.ObjectMapper json) {
    this.db = db;
    this.tx = tx;
    this.json = json;
  }

  public void create(String id) {
    tx.executeWithoutResult(
        s -> {
          String time = RunRepository.now();
          String definition = SeedSuite.snapshot(json);
          db.update(
              "INSERT INTO"
                  + " evaluation_batches(id,suite,status,created_at,finished_at,definition_json,definition_sha256)"
                  + " VALUES(?,?,?,?,?,?,?)",
              id,
              SeedSuite.CODE,
              EvaluationStatus.RUNNING.name(),
              time,
              null,
              definition,
              SeedSuite.sha256(definition));
          int sequence = 0;
          for (SeedCase sample : SeedCase.values()) {
            db.update(
                "INSERT INTO evaluation_cases VALUES(?,?,?,?,?,?,?,?)",
                RunRepository.id(),
                id,
                sample.name(),
                ++sequence,
                CaseStatus.PENDING.name(),
                null,
                null,
                time);
          }
        });
  }

  public void startCase(String batchId, SeedCase sample) {
    int n =
        db.update(
            "UPDATE evaluation_cases SET status=?,updated_at=? WHERE batch_id=? AND case_code=? AND"
                + " status=?",
            CaseStatus.RUNNING.name(),
            RunRepository.now(),
            batchId,
            sample.name(),
            CaseStatus.PENDING.name());
    if (n != 1) throw new IllegalStateException("评测样本不能重复启动。");
  }

  public void attachRun(String batchId, SeedCase sample, String runId) {
    int n =
        db.update(
            "UPDATE evaluation_cases SET run_id=?,updated_at=? WHERE batch_id=? AND case_code=? AND"
                + " status=?",
            runId,
            RunRepository.now(),
            batchId,
            sample.name(),
            CaseStatus.RUNNING.name());
    if (n != 1) throw new IllegalStateException("评测样本已结束，无法关联运行。");
  }

  public void finishCase(String batchId, SeedCase sample, CaseStatus status, String explanation) {
    db.update(
        "UPDATE evaluation_cases SET status=?,explanation=?,updated_at=? WHERE batch_id=? AND"
            + " case_code=? AND status=?",
        status.name(),
        explanation,
        RunRepository.now(),
        batchId,
        sample.name(),
        CaseStatus.RUNNING.name());
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
          String caseId =
              db.queryForObject(
                  "SELECT id FROM evaluation_cases WHERE batch_id=? AND case_code=? AND status=?",
                  String.class,
                  batchId,
                  sample.name(),
                  CaseStatus.RUNNING.name());
          int sequence = 0;
          for (var check : checks) {
            db.update(
                "INSERT INTO evaluation_checks(case_id,sequence_no,check_code,passed,detail)"
                    + " VALUES(?,?,?,?,?)",
                caseId,
                ++sequence,
                check.code().name(),
                check.passed(),
                check.detail());
          }
          boolean passed = checks.stream().allMatch(EvaluationCheck::passed);
          int changed =
              db.update(
                  "UPDATE evaluation_cases SET status=?,explanation=?,updated_at=? WHERE id=? AND"
                      + " status=?",
                  passed ? CaseStatus.PASSED.name() : CaseStatus.FAILED.name(),
                  passed ? "运行状态及关联证据满足固定断言。" : "部分固定断言未通过，请查看断言明细及关联运行。",
                  RunRepository.now(),
                  caseId,
                  CaseStatus.RUNNING.name());
          if (changed != 1) throw new IllegalStateException("评测案例状态已改变，无法保存断言。");
        });
  }

  /** 没有明细表示历史或未完成案例，不能推断各项断言通过。 */
  public List<EvaluationCheck> checks(String caseId) {
    return db.query(
        "SELECT check_code,passed,detail FROM evaluation_checks WHERE case_id=? ORDER BY"
            + " sequence_no",
        (r, i) ->
            new EvaluationCheck(
                EvaluationCheckCode.valueOf(r.getString("check_code")),
                r.getBoolean("passed"),
                r.getString("detail")),
        caseId);
  }

  public void finishBatch(String id, EvaluationStatus status) {
    tx.executeWithoutResult(
        s -> {
          String time = RunRepository.now();
          db.update(
              "UPDATE evaluation_cases SET status=?,explanation=?,updated_at=? WHERE batch_id=? AND"
                  + " status=?",
              CaseStatus.SKIPPED.name(),
              "批次提前结束，样本未执行。",
              time,
              id,
              CaseStatus.PENDING.name());
          db.update(
              "UPDATE evaluation_cases SET status=?,explanation=?,updated_at=? WHERE batch_id=? AND"
                  + " status=?",
              CaseStatus.INTERRUPTED.name(),
              "批次结束时样本尚无确定结果。",
              time,
              id,
              CaseStatus.RUNNING.name());
          db.update(
              "UPDATE evaluation_batches SET status=?,finished_at=? WHERE id=? AND status IN (?,?)",
              status.name(),
              time,
              id,
              EvaluationStatus.RUNNING.name(),
              EvaluationStatus.CANCELLING.name());
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
    int changed =
        db.update(
            "UPDATE evaluation_batches SET status=? WHERE id=? AND status=?",
            EvaluationStatus.CANCELLING.name(),
            id,
            EvaluationStatus.RUNNING.name());
    if (changed != 1) throw new IllegalStateException("评测已经结束或正在取消。");
  }

  public List<EvaluationBatch> list() {
    return db.query(
        "SELECT * FROM evaluation_batches ORDER BY created_at DESC",
        (r, i) ->
            new EvaluationBatch(
                UUID.fromString(r.getString("id")),
                r.getString("suite"),
                EvaluationStatus.valueOf(r.getString("status")),
                Instant.parse(r.getString("created_at")),
                parseTime(r.getString("finished_at")),
                r.getString("definition_json"),
                r.getString("definition_sha256")));
  }

  public EvaluationBatch get(String id) {
    return db.queryForObject(
        "SELECT * FROM evaluation_batches WHERE id=?",
        (r, i) ->
            new EvaluationBatch(
                UUID.fromString(r.getString("id")),
                r.getString("suite"),
                EvaluationStatus.valueOf(r.getString("status")),
                Instant.parse(r.getString("created_at")),
                parseTime(r.getString("finished_at")),
                r.getString("definition_json"),
                r.getString("definition_sha256")),
        id);
  }

  public List<EvaluationCase> cases(String id) {
    return db.query(
        "SELECT * FROM evaluation_cases WHERE batch_id=? ORDER BY sequence_no",
        (r, i) -> {
          SeedCase sample = SeedCase.valueOf(r.getString("case_code"));
          String run = r.getString("run_id");
          return new EvaluationCase(
              UUID.fromString(r.getString("id")),
              sample,
              sample.title(),
              CaseStatus.valueOf(r.getString("status")),
              run == null ? null : UUID.fromString(run),
              r.getString("explanation"),
              Instant.parse(r.getString("updated_at")),
              checks(r.getString("id")));
        },
        id);
  }

  private static Instant parseTime(String value) {
    return value == null ? null : Instant.parse(value);
  }
}
