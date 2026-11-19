package dev.workbench.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 供应方价格版本；用于费用估算与可计价上界预留。 */
@Entity
@Table(name = "provider_price_versions")
public class ProviderPriceVersionEntity {
  @Id
  @Column(name = "id", length = 36)
  private String id;

  @Column(name = "provider", nullable = false, length = 60)
  private String provider;

  @Column(name = "model", nullable = false, length = 80)
  private String model;

  @Column(name = "version", nullable = false)
  private int version;

  @Column(name = "currency", nullable = false, length = 10)
  private String currency;

  @Column(name = "input_micros_per_million", nullable = false)
  private long inputMicrosPerMillion;

  @Column(name = "output_micros_per_million", nullable = false)
  private long outputMicrosPerMillion;

  @Column(name = "input_token_upper_bound", nullable = false)
  private int inputTokenUpperBound;

  @Column(name = "output_token_upper_bound", nullable = false)
  private int outputTokenUpperBound;

  @Column(name = "active", nullable = false)
  private boolean active;

  @Column(name = "created_at", nullable = false, length = 40)
  private String createdAt;

  protected ProviderPriceVersionEntity() {}

  public ProviderPriceVersionEntity(
      String id,
      String provider,
      String model,
      int version,
      String currency,
      long inputMicrosPerMillion,
      long outputMicrosPerMillion,
      int inputTokenUpperBound,
      int outputTokenUpperBound,
      boolean active,
      String createdAt) {
    this.id = id;
    this.provider = provider;
    this.model = model;
    this.version = version;
    this.currency = currency;
    this.inputMicrosPerMillion = inputMicrosPerMillion;
    this.outputMicrosPerMillion = outputMicrosPerMillion;
    this.inputTokenUpperBound = inputTokenUpperBound;
    this.outputTokenUpperBound = outputTokenUpperBound;
    this.active = active;
    this.createdAt = createdAt;
  }

  public String getId() {
    return id;
  }

  public String getProvider() {
    return provider;
  }

  public String getModel() {
    return model;
  }

  public int getVersion() {
    return version;
  }

  public String getCurrency() {
    return currency;
  }

  public long getInputMicrosPerMillion() {
    return inputMicrosPerMillion;
  }

  public long getOutputMicrosPerMillion() {
    return outputMicrosPerMillion;
  }

  public int getInputTokenUpperBound() {
    return inputTokenUpperBound;
  }

  public int getOutputTokenUpperBound() {
    return outputTokenUpperBound;
  }

  public boolean isActive() {
    return active;
  }

  public String getCreatedAt() {
    return createdAt;
  }

  public void deactivate() {
    this.active = false;
  }

  public long upperBoundMicros() {
    return cost(inputTokenUpperBound, outputTokenUpperBound);
  }

  public long cost(long inputTokens, long outputTokens) {
    return Math.addExact(
        Math.multiplyExact(inputTokens, inputMicrosPerMillion) / 1_000_000L,
        Math.multiplyExact(outputTokens, outputMicrosPerMillion) / 1_000_000L);
  }
}
