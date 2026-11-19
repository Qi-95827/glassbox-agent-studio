package dev.workbench.observability;

import dev.workbench.persistence.repository.ObservabilityRepository;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/** 从持久化运行记录生成可定位的本地告警；不推断未记录原因。 */
@Service
public class AlertService {
  private final ObservabilityRepository rows;

  public AlertService(ObservabilityRepository rows) {
    this.rows = rows;
  }

  public List<AlertView> list() {
    List<AlertView> alerts = new ArrayList<>();
    for (var row : rows.recentAlertRuns(50)) {
      String status = row.status();
      alerts.add(
          new AlertView(
              "RUN_" + status,
              "LIMITED".equals(status) ? "WARN" : "ERROR",
              "运行" + status,
              "停止原因：" + (row.stopCause() == null ? "未记录" : row.stopCause())
                  + "；影响：该运行未完成，请查看执行轨迹和证据。",
              row.id(),
              "RUN",
              row.id(),
              row.createdAt()));
    }
    for (var row : rows.recentAlertGitActions(20)) {
      alerts.add(
          new AlertView(
              "GIT_" + row.status(),
              "WARN",
              "Git 动作" + row.status(),
              "类型：" + row.kind() + "；目标：" + row.target() + "；需人工核对。",
              null,
              "GIT_ACTION",
              row.id(),
              row.updatedAt()));
    }
    for (var row : rows.recentFailedEvaluationBatches(20)) {
      alerts.add(
          new AlertView(
              "EVALUATION_FAILED",
              "ERROR",
              "评测批次失败",
              "套件：" + row.suite() + "；影响：批次结果不能用于比较。",
              null,
              "EVALUATION_BATCH",
              row.id(),
              row.finishedAt() == null ? "" : row.finishedAt()));
    }
    return alerts;
  }

  public record AlertView(
      String code,
      String severity,
      String title,
      String detail,
      String runId,
      String targetType,
      String targetId,
      String createdAt) {}
}
