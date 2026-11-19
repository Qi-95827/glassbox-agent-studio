package dev.workbench.persistence.repository;

import dev.workbench.persistence.entity.EvaluationBatchEntity;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 评测批次；定义快照与实际预算配置随行保存。 */
public interface EvaluationBatchRepository
    extends JpaRepository<EvaluationBatchEntity, String> {
  List<EvaluationBatchEntity> findAllByOrderByCreatedAtDesc();

  /** 只允许运行中或正在取消的批次进入终态。 */
  @Transactional(propagation = Propagation.MANDATORY)
  @Modifying
  @Query(
      """
      update EvaluationBatchEntity b set b.status = :status, b.finishedAt = :finishedAt
      where b.id = :id and b.status in :activeStatuses
      """)
  int finish(
      @Param("id") String id,
      @Param("status") String status,
      @Param("finishedAt") String finishedAt,
      @Param("activeStatuses") Collection<String> activeStatuses);

  /** 保存取消请求；只有运行中的批次可以进入取消中。 */
  @Transactional(propagation = Propagation.MANDATORY)
  @Modifying
  @Query(
      """
      update EvaluationBatchEntity b set b.status = :cancelling
      where b.id = :id and b.status = :running
      """)
  int requestCancel(
      @Param("id") String id,
      @Param("cancelling") String cancelling,
      @Param("running") String running);
}
