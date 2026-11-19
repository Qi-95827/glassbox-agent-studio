package dev.workbench.persistence.repository;

import dev.workbench.persistence.entity.EvaluationCaseEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 评测样本状态；条件更新保留“不能重复启动、结束后不能关联运行”的原语义。 */
public interface EvaluationCaseRepository
    extends JpaRepository<EvaluationCaseEntity, String> {
  List<EvaluationCaseEntity> findByBatchIdOrderBySequenceNoAsc(String batchId);

  Optional<EvaluationCaseEntity> findFirstByBatchIdAndCaseCodeAndStatus(
      String batchId, String caseCode, String status);

  @Transactional(propagation = Propagation.MANDATORY)
  @Modifying
  @Query(
      """
      update EvaluationCaseEntity c set c.status = :status, c.updatedAt = :updatedAt
      where c.batchId = :batchId and c.caseCode = :caseCode and c.status = :expected
      """)
  int startCase(
      @Param("batchId") String batchId,
      @Param("caseCode") String caseCode,
      @Param("expected") String expected,
      @Param("status") String status,
      @Param("updatedAt") String updatedAt);

  @Transactional(propagation = Propagation.MANDATORY)
  @Modifying
  @Query(
      """
      update EvaluationCaseEntity c set c.runId = :runId, c.updatedAt = :updatedAt
      where c.batchId = :batchId and c.caseCode = :caseCode and c.status = :expected
      """)
  int attachRun(
      @Param("batchId") String batchId,
      @Param("caseCode") String caseCode,
      @Param("expected") String expected,
      @Param("runId") String runId,
      @Param("updatedAt") String updatedAt);

  @Transactional(propagation = Propagation.MANDATORY)
  @Modifying
  @Query(
      """
      update EvaluationCaseEntity c set c.status = :status, c.explanation = :explanation,
        c.updatedAt = :updatedAt
      where c.batchId = :batchId and c.caseCode = :caseCode and c.status = :expected
      """)
  int finishCase(
      @Param("batchId") String batchId,
      @Param("caseCode") String caseCode,
      @Param("expected") String expected,
      @Param("status") String status,
      @Param("explanation") String explanation,
      @Param("updatedAt") String updatedAt);

  /** 按主键和运行中状态结算，供断言保存后核对是否仍可写。 */
  @Transactional(propagation = Propagation.MANDATORY)
  @Modifying
  @Query(
      """
      update EvaluationCaseEntity c set c.status = :status, c.explanation = :explanation,
        c.updatedAt = :updatedAt
      where c.id = :id and c.status = :expected
      """)
  int finishRunningCase(
      @Param("id") String id,
      @Param("expected") String expected,
      @Param("status") String status,
      @Param("explanation") String explanation,
      @Param("updatedAt") String updatedAt);

  @Transactional(propagation = Propagation.MANDATORY)
  @Modifying
  @Query(
      """
      update EvaluationCaseEntity c set c.status = :status, c.explanation = :explanation,
        c.updatedAt = :updatedAt
      where c.batchId = :batchId and c.status = :expected
      """)
  int finishAllInBatch(
      @Param("batchId") String batchId,
      @Param("expected") String expected,
      @Param("status") String status,
      @Param("explanation") String explanation,
      @Param("updatedAt") String updatedAt);
}
