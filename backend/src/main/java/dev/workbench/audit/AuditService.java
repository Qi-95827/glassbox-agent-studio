package dev.workbench.audit;

import dev.workbench.persistence.entity.AuditEventEntity;
import dev.workbench.persistence.repository.AuditRepository;
import dev.workbench.task.RunRecordService;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

/** 本地操作审计；只记录受控动作、目标、结果和固定说明，不保存密钥或文件正文。 */
@Service
public class AuditService {
  public static final String LOCAL_ACTOR = "local-user";
  private final AuditRepository records;

  public AuditService(AuditRepository records) {
    this.records = records;
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
            RunRecordService.id(),
            LOCAL_ACTOR,
            action,
            targetType,
            targetId,
            outcome == null ? "RECORDED" : outcome,
            safeDetail,
            RunRecordService.now()));
  }

  public List<Map<String, Object>> list(int limit) {
    int bounded = Math.max(1, Math.min(limit, 200));
    return records.findRecent(PageRequest.of(0, bounded)).stream()
        .map(dev.workbench.persistence.repository.AuditEventData::apiFields)
        .toList();
  }
}