package dev.workbench.planning;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.workbench.app.WorkbenchApplication;
import dev.workbench.persistence.repository.PlanCandidateRepository;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 候选图修订的乐观锁必须下推到 SQL UPDATE。
 *
 * <p>回归：旧实现只在 Java 层比较 {@code expectedCandidateSha256}，两条并发修订都先读到相同摘要，再先后写入，
 * 后写者静默覆盖前者。这里直接验证数据库条件更新：摘要不匹配必须影响 0 行，且内容保持先写入者的版本。
 */
@SpringBootTest(
    classes = WorkbenchApplication.class,
    properties = {
      "spring.datasource.url=jdbc:h2:mem:plan_candidate_lock;DB_CLOSE_DELAY=-1",
      "workbench.workspace-root=${java.io.tmpdir}/agent-workbench-candidate-lock"
    })
class PlanCandidateOptimisticLockTest {
  private static final String OWNER = "owner-lock";
  private static final String SHA_A = "a".repeat(64);
  private static final String SHA_B = "b".repeat(64);

  @Autowired PlanCandidateRepository candidates;
  @Autowired JdbcTemplate jdbc;
  @Autowired TransactionTemplate tx;

  private String id;

  @BeforeEach
  void seedReadyCandidate() {
    id = UUID.randomUUID().toString();
    jdbc.update(
        """
        insert into plan_candidates
          (id, owner_scope_key, actor_key, group_id, input_version, request_id, schema_version,
           input_sha256, input_json, status, created_at, candidate_json, candidate_sha256)
        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        id,
        OWNER,
        "actor-lock",
        UUID.randomUUID().toString(),
        1,
        "req-" + UUID.randomUUID(),
        1,
        "c".repeat(64),
        "{}",
        "READY",
        "2026-01-01T00:00:00Z",
        "{\"nodes\":[]}",
        SHA_A);
  }

  @Test
  void staleSummaryIsRejectedInsideTheDatabaseCondition() {
    int stale =
        tx.execute(
            status ->
                candidates.reviseDraft(
                    id, OWNER, "{\"nodes\":[]}", SHA_B, "[]", "d".repeat(64), "READY"));
    assertEquals(0, stale, "期望摘要不匹配必须影响 0 行，而不是条件更新后静默覆盖");
    assertEquals(SHA_A, currentSha(), "被拒绝的修订不得改写任何字节");

    int applied =
        tx.execute(
            status -> candidates.reviseDraft(id, OWNER, "{\"nodes\":[1]}", SHA_B, "[]", SHA_A, "READY"));
    assertEquals(1, applied);
    assertEquals(SHA_B, currentSha());
    assertEquals("{\"nodes\":[1]}", currentJson());

    // 第二个并发修订仍拿着旧摘要 SHA_A：数据库条件必须再次拒绝，先写入者不被覆盖。
    int lostRace =
        tx.execute(
            status -> candidates.reviseDraft(id, OWNER, "{\"nodes\":[2]}", "e".repeat(64), "[]", SHA_A, "READY"));
    assertEquals(0, lostRace);
    assertEquals(SHA_B, currentSha());
    assertEquals("{\"nodes\":[1]}", currentJson(), "后写者不得静默覆盖先写入的修订");
  }

  @Test
  void omittedExpectationStillAllowsEditableRevision() {
    int applied =
        tx.execute(status -> candidates.reviseDraft(id, OWNER, "{\"nodes\":[3]}", SHA_B, "[]", null, "READY"));
    assertEquals(1, applied);
    assertEquals(SHA_B, currentSha());
  }

  private String currentSha() {
    return jdbc.queryForObject("select candidate_sha256 from plan_candidates where id = ?", String.class, id);
  }

  private String currentJson() {
    return jdbc.queryForObject("select candidate_json from plan_candidates where id = ?", String.class, id);
  }
}
