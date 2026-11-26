package dev.workbench.orchestration;

import dev.workbench.persistence.repository.RunData;
import dev.workbench.persistence.repository.RunRepository;
import dev.workbench.store.RunIterationRecordService;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 单个任务下的轮次摘要。
 *
 * <p>一条任务现在可以对应多条运行：第 1 轮是任务创建时的运行，之后每次调改迭代各新增一轮。这里只读投影轮次，供顶部
 * 切换条与前后差异对照消费；恢复子运行不冒充轮次，它们属于同一轮内的恢复尝试。
 */
@Service
public class RunRoundsService {
  private final RunRepository runRows;
  private final RunIterationRecordService iterations;

  public RunRoundsService(RunRepository runRows, RunIterationRecordService iterations) {
    this.runRows = runRows;
    this.iterations = iterations;
  }

  /** 轮次摘要：运行标识、轮次序号、父运行、状态、创建时间与本轮调改意见摘要。 */
  public record Round(
      String runId,
      int roundIndex,
      String parentRunId,
      String status,
      String createdAt,
      String feedbackSummary) {}

  @Transactional(readOnly = true)
  public List<Round> rounds(String taskId) {
    List<RunData> runs = new ArrayList<>(runRows.findByTaskId(taskId));
    runs.sort(
        Comparator.comparing(RunData::createdAt, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(RunData::id));
    Map<String, RunData> byId = new LinkedHashMap<>();
    for (RunData run : runs) byId.put(run.id(), run);
    var notes = iterations.notes(taskId);
    Set<String> roundRuns = new HashSet<>();
    List<Round> result = new ArrayList<>();
    for (var note : notes) {
      RunData run = byId.get(note.runId());
      if (run == null) continue;
      roundRuns.add(run.id());
      result.add(
          new Round(
              run.id(),
              note.roundIndex(),
              run.parentId(),
              run.status(),
              run.createdAt(),
              summary(note.feedback())));
    }
    // 第 1 轮是没有迭代记录的那条最早运行；恢复子运行不进入轮次条。
    for (RunData run : runs) {
      if (roundRuns.contains(run.id())) continue;
      result.add(new Round(run.id(), 1, run.parentId(), run.status(), run.createdAt(), null));
      break;
    }
    result.sort(Comparator.comparingInt(Round::roundIndex));
    return List.copyOf(result);
  }

  private static String summary(String feedback) {
    if (feedback == null) return null;
    String text = feedback.strip();
    return text.length() <= 160 ? text : text.substring(0, 159) + "…";
  }
}
