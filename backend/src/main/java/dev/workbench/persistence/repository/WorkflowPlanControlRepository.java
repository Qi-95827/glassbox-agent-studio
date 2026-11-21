package dev.workbench.persistence.repository;

import dev.workbench.persistence.entity.WorkflowPlanControlEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkflowPlanControlRepository
    extends JpaRepository<WorkflowPlanControlEntity, String> {}
