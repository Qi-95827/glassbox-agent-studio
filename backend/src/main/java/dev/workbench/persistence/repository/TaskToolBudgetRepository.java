package dev.workbench.persistence.repository;

import dev.workbench.persistence.entity.TaskToolBudgetEntity;
import org.springframework.data.jpa.repository.JpaRepository;

/** 任务工具预算的普通写入与按主键读取。 */
public interface TaskToolBudgetRepository
    extends JpaRepository<TaskToolBudgetEntity, TaskToolBudgetEntity.Id> {}