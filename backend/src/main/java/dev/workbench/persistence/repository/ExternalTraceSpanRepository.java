package dev.workbench.persistence.repository;

import dev.workbench.persistence.entity.ExternalTraceSpanEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** 外部 Trace Span：按运行读取，写入顺序由 seq 固定。 */
public interface ExternalTraceSpanRepository
    extends JpaRepository<ExternalTraceSpanEntity, String> {
  List<ExternalTraceSpanEntity> findByRunIdOrderBySeqAsc(String runId);

  int countByRunId(String runId);
}
