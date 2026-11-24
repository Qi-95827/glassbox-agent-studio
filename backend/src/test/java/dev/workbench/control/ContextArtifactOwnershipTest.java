package dev.workbench.control;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import dev.workbench.framework.context.ContextArtifactStore;
import dev.workbench.ownership.OwnershipService;
import dev.workbench.persistence.artifact.JpaContextArtifactStore;
import dev.workbench.persistence.entity.ContextArtifactEntity;
import dev.workbench.persistence.repository.ContextArtifactRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.EmptyResultDataAccessException;

/**
 * L1 上下文产物的隔离与回查归属。
 *
 * <p>反例对应 S-04 第 2 项：引用按内容哈希全局复用，且保存时 {@code runId} 恒为 null，任意用户可凭 引用读取他人源码与工具输出全文。
 */
@ExtendWith(MockitoExtension.class)
class ContextArtifactOwnershipTest {
  @Mock ContextArtifactRepository repository;
  @Mock OwnershipService ownership;

  private ContextArtifactStore.Artifact artifact(String runId, String owner, String content) {
    return new ContextArtifactStore.Artifact(runId, owner, "call-1", "tool-output", content, "[]");
  }

  private JpaContextArtifactStore store() {
    when(repository.existsById(anyString())).thenReturn(false);
    when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    return new JpaContextArtifactStore(repository);
  }

  @Test
  void referencesAreScopedByRunAndOwner() {
    var store = store();

    String first = store.save(artifact("run-1", "USER_A", "相同的工具输出正文"));
    String second = store.save(artifact("run-2", "USER_A", "相同的工具输出正文"));
    String third = store.save(artifact("run-1", "USER_B", "相同的工具输出正文"));

    assertNotEquals(first, second, "不同运行不能共用同一引用");
    assertNotEquals(first, third, "不同账号不能共用同一引用");
    assertNotNull(first);
    assertTrue(first.startsWith("art-"));
  }

  @Test
  void savingSameRunAndOwnerIsIdempotent() {
    var store = store();
    String first = store.save(artifact("run-1", "USER_A", "同一正文"));
    String again = store.save(artifact("run-1", "USER_A", "同一正文"));

    assertEquals(first, again);
  }

  @Test
  void artifactWithoutRunIsNotPersisted() {
    // 没有运行归属时在落盘前就被拒绝，仓储不会被触碰。
    var store = new JpaContextArtifactStore(repository);

    assertNull(store.save(artifact(null, "USER_A", "正文")));
    assertNull(store.save(artifact("  ", "USER_A", "正文")));
    verify(repository, never()).save(any());
  }

  @Test
  void readRequiresOwningTheRun() {
    var entity =
        new ContextArtifactEntity(
            "art-00000000000000a1", "run-1", "call-1", "tool-output", "正文", "[]", "now");
    when(repository.findById("art-00000000000000a1")).thenReturn(Optional.of(entity));

    var service = new ContextArtifactService(repository, ownership);
    var view = service.read("art-00000000000000a1");

    assertEquals("正文", view.get("content"));
    verify(ownership).requireRun("run-1");
  }

  @Test
  void readRejectsLegacyArtifactWithoutRun() {
    var entity =
        new ContextArtifactEntity(
            "art-00000000000000a2", null, "call-1", "tool-output", "历史正文", "[]", "now");
    when(repository.findById("art-00000000000000a2")).thenReturn(Optional.of(entity));

    var service = new ContextArtifactService(repository, ownership);

    assertThrows(EmptyResultDataAccessException.class, () -> service.read("art-00000000000000a2"));
    verify(ownership, never()).requireRun(anyString());
  }

  @Test
  void readPropagatesOwnershipDenial() {
    var entity =
        new ContextArtifactEntity(
            "art-00000000000000a3", "run-9", "call-1", "tool-output", "他人正文", "[]", "now");
    when(repository.findById("art-00000000000000a3")).thenReturn(Optional.of(entity));
    doThrow(new dev.workbench.auth.AuthService.AccessDeniedException("无权访问该资源。"))
        .when(ownership)
        .requireRun("run-9");

    var service = new ContextArtifactService(repository, ownership);

    assertThrows(
        dev.workbench.auth.AuthService.AccessDeniedException.class,
        () -> service.read("art-00000000000000a3"));
  }

  @Test
  void malformedReferenceIsRejectedBeforeLookup() {
    var service = new ContextArtifactService(repository, ownership);

    assertThrows(IllegalArgumentException.class, () -> service.read("../etc/passwd"));
    assertThrows(IllegalArgumentException.class, () -> service.read(null));
    verify(repository, never()).findById(anyString());
  }
}
