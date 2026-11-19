package dev.workbench.persistence.repository;

import dev.workbench.persistence.entity.CostReservationEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CostReservationRepository extends JpaRepository<CostReservationEntity, String> {
  List<CostReservationEntity> findByRunIdOrderByCreatedAtAsc(String runId);
}
