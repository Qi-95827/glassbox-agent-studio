package dev.workbench.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * AgentScope 会话状态表 agentscope_sessions 的映射。
 *
 * <p>沿用框架原有复合键和 JSON 文本格式：session_id 是 {@code 用户命名空间:会话ID}，单值状态使用
 * item_index=0，列表状态每个元素一行，列表哈希另存为 {@code 状态键:_hash}。时间列由数据库默认值维护。
 */
@Entity
@Table(name = "agentscope_sessions")
public class AgentScopeSessionEntity {
  @EmbeddedId private Id id;

  @Lob
  @Column(name = "state_data", nullable = false)
  private String stateData;

  @Column(name = "version", nullable = false)
  private long version;

  @Column(name = "created_at", insertable = false, updatable = false)
  private LocalDateTime createdAt;

  @Column(name = "updated_at", insertable = false, updatable = false)
  private LocalDateTime updatedAt;

  protected AgentScopeSessionEntity() {}

  public AgentScopeSessionEntity(String sessionId, String stateKey, int itemIndex, String data, long version) {
    this.id = new Id(sessionId, stateKey, itemIndex);
    this.stateData = data;
    this.version = version;
  }

  public Id getId() {
    return id;
  }

  public String getStateData() {
    return stateData;
  }

  public long getVersion() {
    return version;
  }

  @Embeddable
  public static class Id implements Serializable {
    @Column(name = "session_id", length = 255)
    private String sessionId;

    @Column(name = "state_key", length = 255)
    private String stateKey;

    @Column(name = "item_index", nullable = false)
    private int itemIndex;

    protected Id() {}

    public Id(String sessionId, String stateKey, int itemIndex) {
      this.sessionId = sessionId;
      this.stateKey = stateKey;
      this.itemIndex = itemIndex;
    }

    public String getSessionId() {
      return sessionId;
    }

    public String getStateKey() {
      return stateKey;
    }

    public int getItemIndex() {
      return itemIndex;
    }

    @Override
    public boolean equals(Object other) {
      return other instanceof Id id
          && itemIndex == id.itemIndex
          && Objects.equals(sessionId, id.sessionId)
          && Objects.equals(stateKey, id.stateKey);
    }

    @Override
    public int hashCode() {
      return Objects.hash(sessionId, stateKey, itemIndex);
    }
  }
}
