package dev.workbench.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

/** workbench_schema_migrations 表的映射；主键由迁移代码预先分配。 */
@Entity
@Table(name = "workbench_schema_migrations")
public class SchemaMigrationEntity implements Persistable<String> {
  @Transient private boolean isNew = true;

  @Id
  @Column(name = "migration_id", length = 100)
  private String migrationId;

  protected SchemaMigrationEntity() {}

  public SchemaMigrationEntity(String migrationId) {
    this.migrationId = migrationId;
  }

  @Override
  public String getId() {
    return migrationId;
  }

  @Override
  public boolean isNew() {
    return isNew;
  }

  @PostLoad
  @PostPersist
  void markNotNew() {
    isNew = false;
  }
}
