package dev.workbench.persistence.repository;

import dev.workbench.persistence.entity.AuditEventEntity;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/** 审计记录的插入与按时间倒序读取。 */
public interface AuditRepository extends JpaRepository<AuditEventEntity, String> {
  @Query(
      """
      select new dev.workbench.persistence.repository.AuditEventData(
        e.id, e.actor, e.action, e.targetType, e.targetId, e.outcome, e.detail, e.createdAt)
      from AuditEventEntity e
      order by e.createdAt desc, e.id desc
      """)
  List<AuditEventData> findRecent(Pageable pageable);
}