package dev.workbench.persistence.repository;

import dev.workbench.persistence.entity.BugCheckCaseEntity;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 检测的逐用例证据；按检查内顺序号读取。 */
public interface BugCheckCaseRepository
    extends JpaRepository<BugCheckCaseEntity, BugCheckCaseEntity.Id> {
  @Query(
      """
      select c from BugCheckCaseEntity c
      where c.id.checkId = :checkId order by c.id.sequenceNo
      """)
  List<BugCheckCaseEntity> findCases(@Param("checkId") String checkId);

  @Query(
      """
      select c from BugCheckCaseEntity c
      where c.id.checkId in :checkIds
      order by c.id.checkId, c.id.sequenceNo
      """)
  List<BugCheckCaseEntity> findCasesForChecks(@Param("checkIds") Collection<String> checkIds);
}
