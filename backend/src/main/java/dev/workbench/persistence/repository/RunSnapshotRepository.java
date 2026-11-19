package dev.workbench.persistence.repository;

import dev.workbench.persistence.entity.RunSnapshotEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RunSnapshotRepository extends JpaRepository<RunSnapshotEntity, String> {
  List<RunSnapshotEntity> findByRunIdOrderByEventSeqAsc(String runId);

  boolean existsByRunIdAndEventSeq(String runId, long eventSeq);
}
