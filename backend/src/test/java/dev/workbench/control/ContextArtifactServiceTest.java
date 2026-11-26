package dev.workbench.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.workbench.ownership.OwnershipService;
import dev.workbench.persistence.entity.ContextArtifactEntity;
import dev.workbench.persistence.repository.ContextArtifactRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * 上下文产物回读的归属防线与长度上限。
 *
 * <p>Agent 运行在异步线程上，没有 HTTP 会话，因此工具路径不能用会话态归属校验；
 * 这里锁住替代方案的核心不变量：只允许读**本运行**自己卸载的产物，且正文必须显式截断。
 */
class ContextArtifactServiceTest {
  private final ContextArtifactRepository artifacts = mock(ContextArtifactRepository.class);
  private final OwnershipService ownership = mock(OwnershipService.class);
  private final ContextArtifactService service = new ContextArtifactService(artifacts, ownership);

  private static ContextArtifactEntity artifact(String runId, String content) {
    return new ContextArtifactEntity(
        "art-abc123", runId, "tool-result-1", "工具输出", content, "[]", "2026-11-26T10:00:00Z");
  }

  @Test
  void readsArtifactOwnedByTheSameRun() {
    when(artifacts.findById("art-abc123"))
        .thenReturn(Optional.of(artifact("run-1", "完整的工具输出正文")));

    var payload = service.readForRun("run-1", "art-abc123", 10_000);

    assertEquals("完整的工具输出正文", payload.get("content"));
    assertEquals(false, payload.get("truncated"));
    // 工具路径不走会话态归属校验：它没有会话可用。
    verify(ownership, never()).requireRun(anyString());
  }

  @Test
  void rejectsArtifactFromAnotherRun() {
    when(artifacts.findById("art-abc123"))
        .thenReturn(Optional.of(artifact("run-2", "别人的运行正文")));

    var rejected =
        assertThrows(
            IllegalArgumentException.class, () -> service.readForRun("run-1", "art-abc123", 10_000));
    // 引用本身不是授权凭证：跨运行必须显式拒绝，而不是静默返回空。
    assertEquals("Artifact 不属于当前运行。", rejected.getMessage());
  }

  @Test
  void truncatesLongBodyAndSaysSo() {
    String body = "x".repeat(500);
    when(artifacts.findById("art-abc123")).thenReturn(Optional.of(artifact("run-1", body)));

    var payload = service.readForRun("run-1", "art-abc123", 100);

    assertEquals(100, String.valueOf(payload.get("content")).length());
    assertEquals(true, payload.get("truncated"));
    assertEquals(500, payload.get("charCount"));
  }

  @Test
  void rejectsMalformedReferenceBeforeTouchingTheDatabase() {
    assertThrows(
        IllegalArgumentException.class, () -> service.readForRun("run-1", "../../etc/passwd", 100));
    assertThrows(IllegalArgumentException.class, () -> service.readForRun("run-1", null, 100));
    verify(artifacts, never()).findById(anyString());
  }

  @Test
  void rejectsMissingRunIdentifier() {
    assertThrows(IllegalArgumentException.class, () -> service.readForRun(" ", "art-abc123", 100));
    verify(artifacts, never()).findById(anyString());
  }

  @Test
  void unknownReferenceIsReportedAsMissing() {
    when(artifacts.findById("art-abc123")).thenReturn(Optional.empty());
    var rejected =
        assertThrows(
            IllegalArgumentException.class, () -> service.readForRun("run-1", "art-abc123", 100));
    assertFalse(rejected.getMessage().isBlank());
    assertTrue(rejected.getMessage().contains("不存在"));
  }
}
