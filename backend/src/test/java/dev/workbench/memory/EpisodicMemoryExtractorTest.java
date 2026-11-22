package dev.workbench.memory;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import dev.workbench.domain.EventType;
import dev.workbench.domain.RunSnapshot;
import dev.workbench.domain.RunStatus;
import dev.workbench.domain.TaskDefinition;
import dev.workbench.framework.event.RunEvent;
import dev.workbench.framework.event.RunEventPersisted;
import dev.workbench.store.RunStateStore;
import dev.workbench.store.TaskRecordService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** 运行结算后只生成 CANDIDATE，且不改变运行终态。 */
class EpisodicMemoryExtractorTest {
  @Test
  void blockedRunCreatesOneToThreeCandidateCardsWithSameSourceRun() {
    var memories = mock(MemoryService.class);
    var runs = mock(RunStateStore.class);
    var tasks = mock(TaskRecordService.class);
    var extractor = new EpisodicMemoryExtractor(memories, runs, tasks);
    String runId = UUID.randomUUID().toString();
    String workspaceId = UUID.randomUUID().toString();
    var task = mock(TaskDefinition.class);
    when(task.memoryEnabled()).thenReturn(true);
    when(task.workspaceId()).thenReturn(workspaceId);
    when(task.fileScopes()).thenReturn(List.of("sort.js", "index.html"));
    when(tasks.taskDefinition(anyString())).thenReturn(task);
    when(runs.runSnapshot(runId))
        .thenReturn(
            new RunSnapshot(
                UUID.fromString(runId),
                UUID.randomUUID(),
                null,
                RunStatus.BLOCKED,
                2,
                1,
                null,
                null,
                null,
                null,
                null,
                null,
                1200,
                null,
                null));
    when(memories.hasSourceRun(runId)).thenReturn(false);
    extractor.onRunEvent(
        new RunEventPersisted(
            new RunEvent(
                1,
                9,
                UUID.fromString(runId),
                EventType.BLOCKED,
                "缺少必需产出：index.html",
                Instant.now(),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null)));
    var content = ArgumentCaptor.forClass(String.class);
    verify(memories, atLeast(2)).createCandidate(
        eq(workspaceId.toString()),
        any(),
        content.capture(),
        eq(runId),
        eq(List.of("sort.js", "index.html")),
        anyDouble());
    assertTrue(content.getAllValues().stream().anyMatch(text -> text.contains("缺少必需产出")));
    assertTrue(content.getAllValues().stream().anyMatch(text -> text.contains("避坑")));
    verify(memories, never()).accept(anyString(), anyString(), anyInt());
  }

  @Test
  void memoryDisabledRunDoesNotExtract() {
    var memories = mock(MemoryService.class);
    var runs = mock(RunStateStore.class);
    var tasks = mock(TaskRecordService.class);
    var extractor = new EpisodicMemoryExtractor(memories, runs, tasks);
    String runId = UUID.randomUUID().toString();
    var task = mock(TaskDefinition.class);
    when(task.memoryEnabled()).thenReturn(false);
    when(tasks.taskDefinition(anyString())).thenReturn(task);
    when(runs.runSnapshot(runId))
        .thenReturn(
            new RunSnapshot(
                UUID.fromString(runId), UUID.randomUUID(), null, RunStatus.FAILED,
                1, 0, null, null, null, null, null, null, 10, null, null));
    extractor.onRunEvent(
        new RunEventPersisted(
            new RunEvent(
                1, 1, UUID.fromString(runId), EventType.FAILED, "失败", Instant.now(),
                null, null, null, null, null, null, null, null)));
    verify(memories, never()).createCandidate(any(), any(), any(), any(), any(), anyDouble());
  }
}
