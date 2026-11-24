package dev.workbench.modelconnection;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import dev.workbench.auth.AuthService;
import dev.workbench.framework.outbound.OutboundUrlGuard;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.EmptyResultDataAccessException;

/** 全局 Embedding 配置的权限与归属边界：只有管理员可改，只能引用自己的已启用连接， Base URL 必须通过出站安全校验。 */
@ExtendWith(MockitoExtension.class)
class RagEmbeddingSettingsSecurityTest {
  @TempDir Path tempDir;
  @Mock ModelConnectionService connections;
  @Mock AuthService auth;

  @BeforeEach
  void setUp() {
    lenient().when(auth.initialized()).thenReturn(true);
  }

  private RagEmbeddingSettings settings(OutboundUrlGuard guard, String fileName) {
    return new RagEmbeddingSettings(
        tempDir.resolve(fileName).toString(),
        false,
        "",
        "",
        "",
        3,
        "server-env-key",
        connections,
        auth,
        guard);
  }

  @Test
  void normalUserCannotChangeGlobalEmbeddingSettings() {
    when(auth.requireAdmin()).thenThrow(new AuthService.AccessDeniedException("该操作只允许管理员执行。"));
    var settings = settings(OutboundUrlGuard.strict(), "a.json");

    assertThrows(
        AuthService.AccessDeniedException.class,
        () ->
            settings.save(
                new RagEmbeddingSettings.Update(true, "c1", "", "text-embedding-3-small", 3)));
    assertFalse(Files.exists(tempDir.resolve("a.json")), "越权请求不得落盘");
    verify(connections, never()).requireEnabledOwned(anyString());
  }

  @Test
  void adminCanReferenceOwnEnabledConnection() {
    when(auth.requireAdmin()).thenReturn(null);
    when(connections.requireEnabledOwned("c1")).thenReturn("c1");
    var settings = settings(OutboundUrlGuard.strict(), "b.json");

    var view =
        settings.save(new RagEmbeddingSettings.Update(true, "c1", "", "text-embedding-3-small", 3));

    assertTrue(view.enabled());
    assertEquals("c1", view.connectionId());
    assertTrue(Files.exists(tempDir.resolve("b.json")));
  }

  @Test
  void foreignOrDisabledConnectionIsRejected() {
    when(auth.requireAdmin()).thenReturn(null);
    when(connections.requireEnabledOwned("c-other"))
        .thenThrow(new EmptyResultDataAccessException(1));
    var settings = settings(OutboundUrlGuard.strict(), "c.json");

    assertThrows(
        EmptyResultDataAccessException.class,
        () -> settings.save(new RagEmbeddingSettings.Update(true, "c-other", "", "m", 3)));
    assertFalse(Files.exists(tempDir.resolve("c.json")));
  }

  @Test
  void metadataBaseUrlIsRejected() {
    when(auth.requireAdmin()).thenReturn(null);
    var settings = settings(OutboundUrlGuard.strict(), "d.json");

    assertThrows(
        IllegalArgumentException.class,
        () ->
            settings.save(
                new RagEmbeddingSettings.Update(true, "", "http://169.254.169.254/v1", "m", 3)));
    assertThrows(
        IllegalArgumentException.class,
        () -> settings.save(new RagEmbeddingSettings.Update(true, "", "https://[::1]/v1", "m", 3)));
    assertFalse(Files.exists(tempDir.resolve("d.json")));
  }

  @Test
  void loopbackBaseUrlRequiresExplicitSwitch() {
    when(auth.requireAdmin()).thenReturn(null);
    var strict = settings(OutboundUrlGuard.strict(), "e.json");
    assertThrows(
        IllegalArgumentException.class,
        () ->
            strict.save(
                new RagEmbeddingSettings.Update(true, "", "http://127.0.0.1:11434/v1", "m", 3)));

    var permissive = settings(OutboundUrlGuard.allowLocalLoopback(), "f.json");
    var view =
        permissive.save(
            new RagEmbeddingSettings.Update(true, "", "http://127.0.0.1:11434/v1", "m", 3));
    assertTrue(view.endpointConfigured());
  }

  @Test
  void singleMachineModeKeepsLocalConfigurationAbility() {
    when(auth.initialized()).thenReturn(false);
    var settings = settings(OutboundUrlGuard.strict(), "g.json");

    var view =
        settings.save(
            new RagEmbeddingSettings.Update(
                true, "", "https://api.openai.com/v1", "text-embedding-3-small", 3));
    assertTrue(view.enabled());
    verify(auth, never()).requireAdmin();
  }

  @Test
  void rejectsSettingWithoutEndpointOrModel() {
    when(auth.requireAdmin()).thenReturn(null);
    var settings = settings(OutboundUrlGuard.strict(), "h.json");
    assertThrows(
        IllegalArgumentException.class,
        () -> settings.save(new RagEmbeddingSettings.Update(true, "", "", "", 3)));
  }

  @Test
  void summaryNeverContainsServerApiKey() {
    var settings = settings(OutboundUrlGuard.strict(), "i.json");
    var summary = settings.summary();
    assertFalse(summary.toString().contains("server-env-key"));
    assertTrue((Boolean) summary.get("apiKeyFromEnvironment"));
  }
}
