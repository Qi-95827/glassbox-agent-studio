package dev.workbench.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Benchmark Hub 下载包状态；大数据集本体只落盘 data/eval-datasets/，不进 Git。 */
@Entity
@Table(name = "eval_benchmark_packs")
public class EvalBenchmarkPackEntity {
  @Id
  @Column(name = "id", length = 36)
  private String id;

  @Column(name = "pack_id", nullable = false, unique = true, length = 60)
  private String packId;

  @Column(name = "category", nullable = false, length = 40)
  private String category;

  @Column(name = "display_name", nullable = false, length = 160)
  private String displayName;

  @Column(name = "source_url", nullable = false, length = 1000)
  private String sourceUrl;

  @Column(name = "license", length = 60)
  private String license;

  @Column(name = "status", nullable = false, length = 30)
  private String status;

  @Column(name = "local_path", length = 1000)
  private String localPath;

  @Column(name = "sha256", length = 64)
  private String sha256;

  @Column(name = "sample_count", nullable = false)
  private int sampleCount;

  @Column(name = "size_bytes", nullable = false)
  private long sizeBytes;

  @Column(name = "downloaded_at", length = 40)
  private String downloadedAt;

  @Column(name = "error_message", length = 1000)
  private String errorMessage;

  @Column(name = "updated_at", nullable = false, length = 40)
  private String updatedAt;

  protected EvalBenchmarkPackEntity() {}

  public EvalBenchmarkPackEntity(
      String id,
      String packId,
      String category,
      String displayName,
      String sourceUrl,
      String license,
      String status,
      String updatedAt) {
    this.id = id;
    this.packId = packId;
    this.category = category;
    this.displayName = displayName;
    this.sourceUrl = sourceUrl;
    this.license = license;
    this.status = status;
    this.updatedAt = updatedAt;
  }

  public String getId() {
    return id;
  }

  public String getPackId() {
    return packId;
  }

  public String getCategory() {
    return category;
  }

  public String getDisplayName() {
    return displayName;
  }

  public String getSourceUrl() {
    return sourceUrl;
  }

  public String getLicense() {
    return license;
  }

  public String getStatus() {
    return status;
  }

  public String getLocalPath() {
    return localPath;
  }

  public String getSha256() {
    return sha256;
  }

  public int getSampleCount() {
    return sampleCount;
  }

  public long getSizeBytes() {
    return sizeBytes;
  }

  public String getDownloadedAt() {
    return downloadedAt;
  }

  public String getErrorMessage() {
    return errorMessage;
  }

  public String getUpdatedAt() {
    return updatedAt;
  }

  public void imported(
      String localPath, String sha256, int sampleCount, long sizeBytes, String at) {
    this.status = "IMPORTED";
    this.localPath = localPath;
    this.sha256 = sha256;
    this.sampleCount = Math.max(0, sampleCount);
    this.sizeBytes = Math.max(0, sizeBytes);
    this.downloadedAt = at;
    this.errorMessage = null;
    this.updatedAt = at;
  }

  public void downloaded(String localPath, String sha256, long sizeBytes, String at) {
    this.status = "DOWNLOADED";
    this.localPath = localPath;
    this.sha256 = sha256;
    this.sizeBytes = Math.max(0, sizeBytes);
    this.downloadedAt = at;
    this.errorMessage = null;
    this.updatedAt = at;
  }

  public void failed(String message, String at) {
    this.status = "FAILED";
    this.errorMessage = message == null || message.isBlank() ? "下载失败" : truncate(message);
    this.updatedAt = at;
  }

  private static String truncate(String value) {
    return value.length() <= 900 ? value : value.substring(0, 899) + "…";
  }
}
