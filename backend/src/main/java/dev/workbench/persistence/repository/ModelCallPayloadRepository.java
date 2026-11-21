package dev.workbench.persistence.repository;

import dev.workbench.persistence.entity.ModelCallPayloadEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ModelCallPayloadRepository extends JpaRepository<ModelCallPayloadEntity, String> {
  List<ModelCallPayloadEntity> findByRunId(String runId);
}
