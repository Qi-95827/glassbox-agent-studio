package dev.workbench.persistence.repository;

import dev.workbench.persistence.entity.ExternalActionCheckEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExternalActionCheckRepository
    extends JpaRepository<ExternalActionCheckEntity, String> {
  List<ExternalActionCheckEntity> findByActionIdOrderByCheckedAtAsc(String actionId);
}
