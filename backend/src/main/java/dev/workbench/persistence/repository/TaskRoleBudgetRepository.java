package dev.workbench.persistence.repository;

import dev.workbench.persistence.entity.TaskRoleBudgetEntity;
import org.springframework.data.jpa.repository.JpaRepository;

/** 任务角色预算的普通写入与按主键读取。 */
public interface TaskRoleBudgetRepository
    extends JpaRepository<TaskRoleBudgetEntity, TaskRoleBudgetEntity.Id> {}