package dev.workbench.persistence.repository;

import dev.workbench.persistence.entity.EntryPolicyEntity;
import org.springframework.data.jpa.repository.JpaRepository;

/** 入口策略按任务主键读写；主键即 task_id。 */
public interface EntryPolicyRepository extends JpaRepository<EntryPolicyEntity, String> {}