package dev.workbench.persistence.repository;

import dev.workbench.persistence.entity.SchemaMigrationEntity;
import org.springframework.data.jpa.repository.JpaRepository;

/** 启动迁移标记的读取与写入。 */
public interface SchemaMigrationRepository extends JpaRepository<SchemaMigrationEntity, String> {}
