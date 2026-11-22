package dev.workbench.memory;

import dev.workbench.domain.EventType;
import dev.workbench.domain.MemoryCategory;
import dev.workbench.domain.RunStatus;
import dev.workbench.framework.event.RunEvent;
import dev.workbench.framework.event.RunEventPersisted;
import dev.workbench.store.RunStateStore;
import dev.workbench.store.TaskRecordService;
import java.util.ArrayList;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 运行结算后提炼 1–3 条情景经验候选卡。
 *
 * <p>系统只写 CANDIDATE，绝不自动注入；用户采纳后才转为 ACTIVE。提炼失败不影响运行结算。
 */
@Component
public class EpisodicMemoryExtractor {
  private final MemoryService memories;
  private final RunStateStore runs;
  private final TaskRecordService tasks;

  public EpisodicMemoryExtractor(
      MemoryService memories, RunStateStore runs, TaskRecordService tasks) {
    this.memories = memories;
    this.runs = runs;
    this.tasks = tasks;
  }

  @EventListener
  public void onRunEvent(RunEventPersisted persisted) {
    if (persisted == null || persisted.event() == null) return;
    RunEvent event = persisted.event();
    if (!terminal(event.type())) return;
    String runId = event.runId().toString();
    try {
      if (memories.hasSourceRun(runId)) return;
      var run = runs.runSnapshot(runId);
      var task = tasks.taskDefinition(run.taskId().toString());
      if (!task.memoryEnabled() || task.workspaceId() == null) return;
      var paths = task.fileScopes().stream().limit(5).toList();
      var cards = new ArrayList<Card>();
      cards.add(
          new Card(
              MemoryCategory.PROJECT_BACKGROUND,
              "运行 " + shortId(runId) + " 结果：" + trim(event.message(), 180),
              0.55));
      cards.add(
          new Card(
              MemoryCategory.PROJECT_CONVENTION,
              "本次运行消耗模型轮次 "
                  + run.rounds()
                  + "、工具调用 "
                  + run.tools()
                  + "；状态 "
                  + run.status()
                  + "。",
              0.45));
      if (run.status() == RunStatus.BLOCKED
          || run.status() == RunStatus.FAILED
          || run.status() == RunStatus.LIMITED) {
        cards.add(
            new Card(
                MemoryCategory.PRECAUTION,
                "避坑卡：下次遇到相同任务时先检查「"
                    + trim(event.message(), 160)
                    + "」，避免重复无效尝试。",
                0.50));
      }
      for (var card : cards) {
        try {
          memories.createCandidate(
              task.workspaceId().toString(),
              card.category(),
              card.content(),
              runId,
              paths,
              card.confidence());
        } catch (RuntimeException ignored) {
          // 候选上限或单卡冲突不阻断其余卡片。
        }
      }
    } catch (RuntimeException ignored) {
      // 记忆提炼是旁路能力，绝不改变运行终态。
    }
  }

  private static boolean terminal(EventType type) {
    return switch (type) {
      case RUN_COMPLETED, BLOCKED, FAILED, LIMITED, CANCELLED, INTERRUPTED -> true;
      default -> false;
    };
  }

  private static String shortId(String id) {
    return id == null || id.length() < 8 ? String.valueOf(id) : id.substring(0, 8);
  }

  private static String trim(String text, int max) {
    String value = text == null ? "" : text.strip();
    return value.length() <= max ? value : value.substring(0, max - 1) + "…";
  }

  private record Card(MemoryCategory category, String content, double confidence) {}
}
