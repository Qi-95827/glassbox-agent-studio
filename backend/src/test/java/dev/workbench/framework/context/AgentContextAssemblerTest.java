package dev.workbench.framework.context;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.workbench.domain.EventType;
import dev.workbench.domain.TaskDefinition;
import dev.workbench.memory.MemoryService;
import dev.workbench.task.RunRepository;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 上下文来源按确定的顺序执行，记忆仅在用户授权时装配。 */
class AgentContextAssemblerTest {
  @Test
  void sortsByOrderThenIdAndRejectsConflictingOrInvalidSources() {
    var input = new AgentContextInput(mock(TaskDefinition.class), "run", null);
    var assembler =
        new AgentContextAssembler(
            List.of(source("b", 20, "B"), source("c", 10, "C"), source("a", 20, "A")));
    assertEquals("CAB", assembler.assemble(input));
    assertThrows(
        IllegalArgumentException.class,
        () -> new AgentContextAssembler(List.of(source("a", 1, "1"), source("a", 2, "2"))));
    assertThrows(
        IllegalStateException.class,
        () -> new AgentContextAssembler(List.of(source("a", 1, null))).assemble(input));
    assertThrows(
        IllegalStateException.class, () -> new AgentContextAssembler(List.of()).assemble(input));
  }

  @Test
  void memoryRequiresExplicitTaskAuthorizationAndRecordsLoading() {
    var memories = mock(MemoryService.class);
    var repository = mock(RunRepository.class);
    var task = mock(TaskDefinition.class);
    var contributor =
        new BuiltInContextContributors()
            .projectMemoryContext(new ObjectMapper(), memories, repository);
    var input = new AgentContextInput(task, "run", null);
    assertEquals("", contributor.contribute(input));
    verifyNoInteractions(memories, repository);
    when(task.memoryEnabled()).thenReturn(true);
    when(task.workspaceId()).thenReturn("workspace");
    when(memories.loadContextAndRecord("run", "workspace")).thenReturn(List.of());
    assertTrue(contributor.contribute(input).contains("低可信数据"));
    verify(memories).loadContextAndRecord("run", "workspace");
    verify(repository).event(eq("run"), eq(EventType.MEMORY_LOADED), contains("0 条"));
  }

  private static AgentContextContributor source(String id, int order, String value) {
    return new AgentContextContributor() {
      public String id() {
        return id;
      }

      public int order() {
        return order;
      }

      public String contribute(AgentContextInput input) {
        return value;
      }
    };
  }
}
