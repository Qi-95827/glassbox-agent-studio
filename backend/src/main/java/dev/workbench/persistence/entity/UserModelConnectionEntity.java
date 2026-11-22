package dev.workbench.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

/** 用户自带的模型连接；密钥列只保存服务端密文，掩码列用于列表回显。 */
@Entity
@Table(name = "user_model_connections")
public class UserModelConnectionEntity implements Persistable<String> {
  @Transient private boolean isNew = true;

  @Id
  @Column(name = "id", length = 36)
  private String id;

  @Column(name = "owner_user_id", nullable = false, length = 36)
  private String ownerUserId;

  @Column(name = "display_name", nullable = false, length = 80)
  private String displayName;

  @Column(name = "provider_type", nullable = false, length = 40)
  private String providerType;

  @Column(name = "base_url", nullable = false, length = 500)
  private String baseUrl;

  @Column(name = "model_name", nullable = false, length = 120)
  private String modelName;

  @Column(name = "encrypted_api_key", nullable = false, columnDefinition = "TEXT")
  private String encryptedApiKey;

  @Column(name = "key_masked", nullable = false, length = 60)
  private String keyMasked;

  @Column(name = "enabled", nullable = false)
  private boolean enabled;

  @Column(name = "is_default", nullable = false)
  private boolean defaultFlag;

  @Column(name = "input_price_per_1m")
  private Double inputPricePer1m;

  @Column(name = "output_price_per_1m")
  private Double outputPricePer1m;

  @Column(name = "currency", nullable = false, length = 10)
  private String currency;

  @Column(name = "last_test_status", length = 20)
  private String lastTestStatus;

  @Column(name = "last_test_message", length = 500)
  private String lastTestMessage;

  @Column(name = "last_tested_at", length = 40)
  private String lastTestedAt;

  @Column(name = "created_at", nullable = false, length = 40)
  private String createdAt;

  @Column(name = "updated_at", nullable = false, length = 40)
  private String updatedAt;

  protected UserModelConnectionEntity() {}

  public UserModelConnectionEntity(
      String id,
      String ownerUserId,
      String displayName,
      String providerType,
      String baseUrl,
      String modelName,
      String encryptedApiKey,
      String keyMasked,
      boolean enabled,
      boolean defaultFlag,
      Double inputPricePer1m,
      Double outputPricePer1m,
      String currency,
      String createdAt,
      String updatedAt) {
    this.id = id;
    this.ownerUserId = ownerUserId;
    this.displayName = displayName;
    this.providerType = providerType;
    this.baseUrl = baseUrl;
    this.modelName = modelName;
    this.encryptedApiKey = encryptedApiKey;
    this.keyMasked = keyMasked;
    this.enabled = enabled;
    this.defaultFlag = defaultFlag;
    this.inputPricePer1m = inputPricePer1m;
    this.outputPricePer1m = outputPricePer1m;
    this.currency = currency;
    this.createdAt = createdAt;
    this.updatedAt = updatedAt;
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

  public String getOwnerUserId() {
    return ownerUserId;
  }

  public String getDisplayName() {
    return displayName;
  }

  public void setDisplayName(String displayName) {
    this.displayName = displayName;
  }

  public String getProviderType() {
    return providerType;
  }

  public void setProviderType(String providerType) {
    this.providerType = providerType;
  }

  public String getBaseUrl() {
    return baseUrl;
  }

  public void setBaseUrl(String baseUrl) {
    this.baseUrl = baseUrl;
  }

  public String getModelName() {
    return modelName;
  }

  public void setModelName(String modelName) {
    this.modelName = modelName;
  }

  public String getEncryptedApiKey() {
    return encryptedApiKey;
  }

  public void setEncryptedApiKey(String encryptedApiKey) {
    this.encryptedApiKey = encryptedApiKey;
  }

  public String getKeyMasked() {
    return keyMasked;
  }

  public void setKeyMasked(String keyMasked) {
    this.keyMasked = keyMasked;
  }

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public boolean isDefaultFlag() {
    return defaultFlag;
  }

  public void setDefaultFlag(boolean defaultFlag) {
    this.defaultFlag = defaultFlag;
  }

  public Double getInputPricePer1m() {
    return inputPricePer1m;
  }

  public void setInputPricePer1m(Double inputPricePer1m) {
    this.inputPricePer1m = inputPricePer1m;
  }

  public Double getOutputPricePer1m() {
    return outputPricePer1m;
  }

  public void setOutputPricePer1m(Double outputPricePer1m) {
    this.outputPricePer1m = outputPricePer1m;
  }

  public String getCurrency() {
    return currency;
  }

  public void setCurrency(String currency) {
    this.currency = currency;
  }

  public String getLastTestStatus() {
    return lastTestStatus;
  }

  public String getLastTestMessage() {
    return lastTestMessage;
  }

  public String getLastTestedAt() {
    return lastTestedAt;
  }

  public void setLastTest(String status, String message, String testedAt) {
    this.lastTestStatus = status;
    this.lastTestMessage = message;
    this.lastTestedAt = testedAt;
  }

  public String getCreatedAt() {
    return createdAt;
  }

  public String getUpdatedAt() {
    return updatedAt;
  }

  public void setUpdatedAt(String updatedAt) {
    this.updatedAt = updatedAt;
  }
}
