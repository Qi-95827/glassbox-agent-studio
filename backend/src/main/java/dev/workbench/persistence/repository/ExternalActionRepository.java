package dev.workbench.persistence.repository;

import dev.workbench.persistence.entity.ExternalActionEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExternalActionRepository extends JpaRepository<ExternalActionEntity, String> {
  Optional<ExternalActionEntity> findByActionKey(String actionKey);

  List<ExternalActionEntity> findByRunIdOrderByCreatedAtAsc(String runId);

  List<ExternalActionEntity> findByStatusOrderByCreatedAtAsc(String status);
}
