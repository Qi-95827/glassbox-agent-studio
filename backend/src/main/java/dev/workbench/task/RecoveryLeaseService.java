package dev.workbench.task;

import dev.workbench.persistence.entity.RecoveryLeaseEntity;
import dev.workbench.persistence.repository.RecoveryLeaseRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 跨进程恢复租约。
 *
 * <p>租约在数据库行锁内获取；未过期租约拒绝第二个恢复者，过期租约允许接管。成功恢复后释放，进程中断后由到期时间兜底。
 */
@Service
public class RecoveryLeaseService {
  private final RecoveryLeaseRepository leases;

  public RecoveryLeaseService(RecoveryLeaseRepository leases) {
    this.leases = leases;
  }

  @Transactional
  public Lease acquire(String runId, String owner, Duration ttl) {
    if (owner == null || owner.isBlank() || owner.length() > 120)
      throw new IllegalArgumentException("恢复持有者标识无效。");
    if (ttl == null || ttl.isNegative() || ttl.isZero() || ttl.toSeconds() > 3600)
      throw new IllegalArgumentException("恢复租约时长须在 1 秒至 1 小时之间。");
    Instant now = Instant.now();
    var existing = leases.lockByRunId(runId).orElse(null);
    if (existing != null && "ACTIVE".equals(existing.getStatus())) {
      Instant expires = Instant.parse(existing.getExpiresAt());
      if (expires.isAfter(now))
        throw new IllegalStateException(
            "该运行正在被另一个恢复者处理，租约到期时间 " + existing.getExpiresAt() + "。");
      existing.expire();
      leases.save(existing);
    }
    String token = RunRecordService.id();
    String acquired = now.toString();
    String expires = now.plus(ttl).toString();
    if (existing == null) {
      existing = new RecoveryLeaseEntity(runId, owner, token, acquired, expires, "ACTIVE");
    } else {
      existing.acquire(owner, token, acquired, expires);
    }
    leases.save(existing);
    return new Lease(runId, token, owner, acquired, expires);
  }

  @Transactional
  public void release(String runId, String token) {
    leases
        .lockByRunId(runId)
        .filter(row -> token != null && token.equals(row.getLeaseToken()))
        .ifPresent(
            row -> {
              row.release();
              leases.save(row);
            });
  }

  public Map<String, Object> find(String runId) {
    return leases
        .findById(runId)
        .map(
            row -> {
              Map<String, Object> item = new LinkedHashMap<>();
              item.put("RUN_ID", row.getRunId());
              item.put("OWNER", row.getLeaseOwner());
              item.put("STATUS", row.getStatus());
              item.put("ACQUIRED_AT", row.getAcquiredAt());
              item.put("EXPIRES_AT", row.getExpiresAt());
              return item;
            })
        .orElse(null);
  }

  public record Lease(String runId, String token, String owner, String acquiredAt, String expiresAt) {}
}
