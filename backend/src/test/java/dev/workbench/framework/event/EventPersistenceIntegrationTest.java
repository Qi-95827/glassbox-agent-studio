package dev.workbench.framework.event;

import static org.junit.jupiter.api.Assertions.*;

import dev.workbench.app.WorkbenchApplication;
import dev.workbench.domain.EventType;
import dev.workbench.domain.TaskEntryKind;
import dev.workbench.orchestration.CreateTaskRequest;
import dev.workbench.orchestration.RunService;
import dev.workbench.task.RunRecordService;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** 同一运行并发写事件时，运行内序号与游标分页保持无遗漏、无重复、无串运行。 */
@SpringBootTest(
    classes = WorkbenchApplication.class,
    properties = {
      "spring.datasource.url=jdbc:h2:mem:eventpersistence;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
      "workbench.workspace-root=${java.io.tmpdir}/agent-workbench-eventpersistence"
    })
class EventPersistenceIntegrationTest {
  @Autowired RunService runs;
  @Autowired RunRecordService repository;
  @Autowired EventQueryService events;

  @Test
  void concurrentWritesAndCursorPagingHaveNoGapsDuplicatesOrCrossRunLeak() throws Exception {
    String first = runs.create(request("并发事件-" + UUID.randomUUID()));
    String second = runs.create(request("隔离事件-" + UUID.randomUUID()));
    int concurrent = 20;
    var gate = new CountDownLatch(1);
    var pool = Executors.newFixedThreadPool(8);
    try {
      var futures = new ArrayList<java.util.concurrent.Future<?>>();
      for (int i = 0; i < concurrent; i++) {
        int index = i;
        futures.add(
            pool.submit(
                () -> {
                  gate.await();
                  repository.event(first, EventType.RUN_STARTED, "并发事件-" + index);
                  return null;
                }));
      }
      gate.countDown();
      for (var future : futures) future.get(30, TimeUnit.SECONDS);
    } finally {
      pool.shutdownNow();
    }
    for (int i = 0; i < 3; i++)
      repository.event(second, EventType.RUN_STARTED, "隔离事件-" + i);

    List<RunEvent> collected = new ArrayList<>();
    long cursor = 0;
    while (true) {
      var page = events.page(first, cursor, 4);
      if (page.events().isEmpty()) break;
      assertTrue(page.nextCursor() > cursor, "非空页必须推进游标。");
      collected.addAll(page.events());
      cursor = page.nextCursor();
    }

    assertEquals(concurrent + 1, collected.size());
    assertEquals(concurrent + 1, new HashSet<>(collected.stream().map(RunEvent::id).toList()).size());
    assertTrue(collected.stream().allMatch(event -> first.equals(event.runId().toString())));
    assertTrue(
        collected.stream().noneMatch(event -> event.message().startsWith("隔离事件-")));
    assertEquals(
        java.util.stream.LongStream.rangeClosed(1, concurrent + 1).boxed().toList(),
        collected.stream().map(RunEvent::runSeq).toList(),
        "同一运行的事件序号必须连续。");
    assertEquals(collected.size(), repository.events(first).size());

    var secondPage = events.page(second, 0, 200);
    assertTrue(secondPage.events().stream().allMatch(event -> second.equals(event.runId().toString())));
    assertTrue(secondPage.events().stream().anyMatch(event -> "隔离事件-0".equals(event.message())));
  }

  private CreateTaskRequest request(String goal) {
    return new CreateTaskRequest(
        goal,
        "normal",
        4,
        2,
        30,
        "mock",
        null,
        null,
        false,
        null,
        false,
        null,
        List.of(),
        List.of(),
        null,
        null,
        TaskEntryKind.GENERAL,
        null,
        null,
        null,
        null,
        null,
        "dual-analysis",
        true,
        null);
  }
}
