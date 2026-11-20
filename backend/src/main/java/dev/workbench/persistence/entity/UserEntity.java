package dev.workbench.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

/** 本地账号；密码只保存 PBKDF2 摘要，不保存明文或会话令牌。 */
@Entity
@Table(name = "users")
public class UserEntity implements Persistable<String> {
  @Transient private boolean isNew = true;

  @Id
  @Column(name = "id", length = 36)
  private String id;

  @Column(name = "username", nullable = false, length = 32)
  private String username;

  @Column(name = "display_name", nullable = false, length = 120)
  private String displayName;

  @Column(name = "password_hash", nullable = false, length = 300)
  private String passwordHash;

  @Column(name = "role", nullable = false, length = 20)
  private String role;

  @Column(name = "status", nullable = false, length = 20)
  private String status;

  @Column(name = "legacy_claimed", nullable = false)
  private boolean legacyClaimed;

  @Column(name = "created_at", nullable = false, length = 40)
  private String createdAt;

  protected UserEntity() {}

  public UserEntity(
      String id,
      String username,
      String displayName,
      String passwordHash,
      String role,
      String status,
      boolean legacyClaimed,
      String createdAt) {
    this.id = id;
    this.username = username;
    this.displayName = displayName;
    this.passwordHash = passwordHash;
    this.role = role;
    this.status = status;
    this.legacyClaimed = legacyClaimed;
    this.createdAt = createdAt;
  }

  @Override
  public String getId() {
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

  public String getUsername() {
    return username;
  }

  public String getDisplayName() {
    return displayName;
  }

  public String getPasswordHash() {
    return passwordHash;
  }

  public String getRole() {
    return role;
  }

  public String getStatus() {
    return status;
  }

  public boolean isLegacyClaimed() {
    return legacyClaimed;
  }

  public String getCreatedAt() {
    return createdAt;
  }

  public void setLegacyClaimed(boolean legacyClaimed) {
    this.legacyClaimed = legacyClaimed;
  }

  public void setStatus(String status) {
    this.status = status;
  }
}
