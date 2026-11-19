package dev.workbench.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

/** 一次运行实际装配过项目记忆的标记；run_id 唯一，重复装配应被拒绝。 */
@Entity
@Table(name = "run_memory_loads")
public class RunMemoryLoadEntity implements Persistable<String> {
  @Transient private boolean isNew = true;

  @Id
  @Column(name = "run_id", length = 36)
  private String runId;

  @Column(name = "workspace_id", nullable = false, length = 36)
  private String workspaceId;

  @Column(name = "item_count", nullable = false)
  private int itemCount;

  @Column(name = "loaded_at", nullable = false, length = 40)
  private String loadedAt;

  protected RunMemoryLoadEntity() {}

  public RunMemoryLoadEntity(String runId, String workspaceId, int itemCount, String loadedAt) {
    this.runId = runId;
    this.workspaceId = workspaceId;
    this.itemCount = itemCount;
    this.loadedAt = loadedAt;
  }

  @Override
  public String getId() {
    return runId;
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

  public String getWorkspaceId() {
    return workspaceId;
  }

  public int getItemCount() {
    return itemCount;
  }

  public String getLoadedAt() {
    return loadedAt;
  }
}
