package dev.workbench.persistence.repository;

import dev.workbench.persistence.entity.SourceArtifactEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SourceArtifactRepository extends JpaRepository<SourceArtifactEntity, String> {
  List<SourceArtifactEntity> findByTaskIdOrderByCreatedAtAsc(String taskId);

  boolean existsByTaskIdAndKindAndSourceRunId(String taskId, String kind, String sourceRunId);
}
