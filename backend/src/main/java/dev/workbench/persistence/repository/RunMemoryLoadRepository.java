package dev.workbench.persistence.repository;

import dev.workbench.persistence.entity.RunMemoryLoadEntity;
import org.springframework.data.jpa.repository.JpaRepository;

/** 运行记忆装配标记；run_id 为主键，重复装配由数据库拒绝。 */
public interface RunMemoryLoadRepository extends JpaRepository<RunMemoryLoadEntity, String> {}
