package dev.workbench.audit;

import dev.workbench.auth.AuthService;
import dev.workbench.persistence.entity.AuditEventEntity;
import dev.workbench.persistence.repository.AuditRepository;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

/**
 * 本地操作审计；只记录受控动作、目标、结果和固定说明，不保存密钥或文件正文。
 *
 * <p>操作人必须是真实身份：多用户模式下写死 {@code local-user} 会让所有操作无法追责。有登录会话时 记录账号名；未初始化账号的单机模式记为 {@code
 * local-user}；后台系统动作记为 {@code system}。
 */
@Service
public class AuditService {
  public static final String LOCAL_ACTOR = "local-user";
  public static final String SYSTEM_ACTOR = "system";
  private final AuditRepository records;
  private final AuthService auth;

  public AuditService(AuditRepository records, AuthService auth) {
    this.records = records;
    this.auth = auth;
  }

  public void record(
      String action, String targetType, String targetId, String outcome, String detail) {
    if (action == null || action.isBlank() || targetType == null || targetType.isBlank())
      throw new IllegalArgumentException("审计动作和目标类型不能为空。");
    String safeDetail = detail == null ? null : detail.strip();
    if (safeDetail != null && safeDetail.length() > 1000)
      safeDetail = safeDetail.substring(0, 1000);
    records.save(
        new AuditEventEntity(
            dev.workbench.domain.WorkbenchIds.newId(),
            actor(),
            action,
            targetType,
            targetId,
            outcome == null ? "RECORDED" : outcome,
            safeDetail,
            dev.workbench.domain.WorkbenchTime.now()));
  }

  /** 解析真实操作人；解析失败时按“系统动作”或单机本地兜底，绝不静默记成某个真实账号。 */
  private String actor() {
    try {
      var current = auth.current();
      if (current.isPresent()) return current.get().getUsername();
      return auth.initialized() ? SYSTEM_ACTOR : LOCAL_ACTOR;
    } catch (RuntimeException e) {
      return auth.initialized() ? SYSTEM_ACTOR : LOCAL_ACTOR;
    }
  }

  public List<Map<String, Object>> list(int limit) {
    int bounded = Math.max(1, Math.min(limit, 200));
    return records.findRecent(PageRequest.of(0, bounded)).stream()
        .map(dev.workbench.persistence.repository.AuditEventData::apiFields)
        .toList();
  }
}
