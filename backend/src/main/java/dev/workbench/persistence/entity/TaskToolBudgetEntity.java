package dev.workbench.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.io.Serializable;
import java.util.Objects;
import org.springframework.data.domain.Persistable;

/** 任务工具预算；v2 表包含搜索工具，沿用既有迁移结果。 */
@Entity
@Table(name = "task_tool_budgets_v2")
public class TaskToolBudgetEntity implements Persistable<TaskToolBudgetEntity.Id> {
  @Transient private boolean isNew = true;
  @EmbeddedId private Id id;

  @Column(name = "policy_version", nullable = false)
  private int policyVersion;

  @Column(name = "max_calls", nullable = false)
  private int maxCalls;

  protected TaskToolBudgetEntity() {}

  public TaskToolBudgetEntity(String taskId, String toolKind, int policyVersion, int maxCalls) {
    this.id = new Id(taskId, toolKind);
    this.policyVersion = policyVersion;
    this.maxCalls = maxCalls;
  }

  public Id getId() {
    return id;
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

  @Embeddable
  public static class Id implements Serializable {
    @Column(name = "task_id", length = 36)
    private String taskId;

    @Column(name = "tool_kind", length = 40)
    private String toolKind;

    protected Id() {}

    public Id(String taskId, String toolKind) {
      this.taskId = taskId;
      this.toolKind = toolKind;
    }

    public String getTaskId() {
      return taskId;
    }

    public String getToolKind() {
      return toolKind;
    }

    @Override
    public boolean equals(Object other) {
      if (this == other) return true;
      if (!(other instanceof Id id)) return false;
      return Objects.equals(taskId, id.taskId) && Objects.equals(toolKind, id.toolKind);
    }

    @Override
    public int hashCode() {
      return Objects.hash(taskId, toolKind);
    }
  }
}