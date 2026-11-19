package dev.workbench.persistence.repository;

import dev.workbench.persistence.entity.EvaluationCheckEntity;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 样本固定断言；按顺序号读取，保留无明细即未通过的含义。 */
public interface EvaluationCheckRepository
    extends JpaRepository<EvaluationCheckEntity, EvaluationCheckEntity.Id> {
  @Query(
      """
      select c from EvaluationCheckEntity c
      where c.id.caseId = :caseId order by c.sequenceNo
      """)
  List<EvaluationCheckEntity> findChecks(@Param("caseId") String caseId);

  @Query(
      """
      select c from EvaluationCheckEntity c
      where c.id.caseId in :caseIds
      order by c.id.caseId, c.sequenceNo
      """)
  List<EvaluationCheckEntity> findChecksForCases(@Param("caseIds") Collection<String> caseIds);
}
