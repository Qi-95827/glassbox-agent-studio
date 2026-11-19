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

/** 任务角色预算；主键由既有复合键 (task_id, role_code) 表示。 */
@Entity
@Table(name = "task_role_budgets")
public class TaskRoleBudgetEntity implements Persistable<TaskRoleBudgetEntity.Id> {
  @Transient private boolean isNew = true;
  @EmbeddedId private Id id;

  @Column(name = "policy_version", nullable = false)
  private int policyVersion;

  @Column(name = "max_model_calls")
  private Integer maxModelCalls;

  @Column(name = "max_tool_calls")
  private Integer maxToolCalls;

  protected TaskRoleBudgetEntity() {}

  public TaskRoleBudgetEntity(
      String taskId,
      String roleCode,
      int policyVersion,
      Integer maxModelCalls,
      Integer maxToolCalls) {
    this.id = new Id(taskId, roleCode);
    this.policyVersion = policyVersion;
    this.maxModelCalls = maxModelCalls;
    this.maxToolCalls = maxToolCalls;
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

    @Column(name = "role_code", length = 30)
    private String roleCode;

    protected Id() {}

    public Id(String taskId, String roleCode) {
      this.taskId = taskId;
      this.roleCode = roleCode;
    }

    public String getTaskId() {
      return taskId;
    }

    public String getRoleCode() {
      return roleCode;
    }

    @Override
    public boolean equals(Object other) {
      if (this == other) return true;
      if (!(other instanceof Id id)) return false;
      return Objects.equals(taskId, id.taskId) && Objects.equals(roleCode, id.roleCode);
    }

    @Override
    public int hashCode() {
      return Objects.hash(taskId, roleCode);
    }
  }
}