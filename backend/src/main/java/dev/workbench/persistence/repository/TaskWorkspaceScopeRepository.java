package dev.workbench.persistence.repository;

import dev.workbench.persistence.entity.TaskWorkspaceScopeEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TaskWorkspaceScopeRepository
    extends JpaRepository<TaskWorkspaceScopeEntity, String> {}
