package dev.workbench.modelconnection;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.workbench.auth.AuthService;
import dev.workbench.framework.outbound.OutboundUrlGuard;
import dev.workbench.framework.secret.SecretCipher;
import dev.workbench.persistence.entity.UserEntity;
import dev.workbench.persistence.repository.UserModelConnectionRepository;
import java.security.SecureRandom;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 宿主机遗留明文密钥 {@code config/deepseek-api-key.txt} 的使用边界。
 *
 * <p>反例对应 S-11：多用户模式下普通用户直接选择 {@code deepseek} 模式即可白用管理员的全局额度。
 */
@ExtendWith(MockitoExtension.class)
class LegacyKeyAccessTest {
  @Mock UserModelConnectionRepository connections;
  @Mock ModelProbe probe;
  @Mock AuthService auth;

  private ModelConnectionService service;

  @BeforeEach
  void setUp() {
    byte[] key = new byte[32];
    new SecureRandom(new byte[] {4, 2, 6}).nextBytes(key);
    service =
        new ModelConnectionService(
            connections, probe, auth, new SecretCipher(key), OutboundUrlGuard.strict());
  }

  private void login(String role, boolean legacyClaimed) {
    UserEntity user = mock(UserEntity.class);
    // 不同用例触达的字段不同，统一放宽以免严格桩模式误报未使用。
    lenient().when(user.getId()).thenReturn("u1");
    lenient().when(user.getRole()).thenReturn(role);
    lenient().when(user.isLegacyClaimed()).thenReturn(legacyClaimed);
    when(auth.initialized()).thenReturn(true);
    lenient().when(auth.current()).thenReturn(Optional.of(user));
  }

  @Test
  void singleMachineModeKeepsLegacyKey() {
    when(auth.initialized()).thenReturn(false);

    assertTrue(service.legacyKeyAllowed());
    assertDoesNotThrow(service::requireLegacyKeyAccess);
  }

  @Test
  void ordinaryUserCannotUseHostKey() {
    login("USER", false);

    assertFalse(service.legacyKeyAllowed());
    var denied =
        assertThrows(AuthService.AccessDeniedException.class, service::requireLegacyKeyAccess);
    assertTrue(
        denied.getMessage().contains("管理员") || denied.getMessage().contains("自己的模型连接"),
        denied.getMessage());
  }

  @Test
  void ordinaryUserWhoClaimedLegacyHistoryKeepsAccess() {
    login("USER", true);

    assertTrue(service.legacyKeyAllowed());
    assertDoesNotThrow(service::requireLegacyKeyAccess);
  }

  @Test
  void administratorCanAlwaysUseHostKey() {
    login("ADMIN", false);

    assertTrue(service.legacyKeyAllowed());
    assertDoesNotThrow(service::requireLegacyKeyAccess);
  }

  @Test
  void backgroundThreadWithoutSessionCannotUseHostKey() {
    when(auth.initialized()).thenReturn(true);
    when(auth.current()).thenReturn(Optional.empty());

    assertFalse(service.legacyKeyAllowed());
    assertThrows(AuthService.AccessDeniedException.class, service::requireLegacyKeyAccess);
  }

  @Test
  void listHidesLegacyEntryFromOrdinaryUsers() {
    byte[] key = new byte[32];
    new SecureRandom(new byte[] {4, 2, 6}).nextBytes(key);
    var withLegacyKeyFile =
        new ModelConnectionService(
            connections, probe, auth, new SecretCipher(key), OutboundUrlGuard.strict(), () -> true);
    login("USER", false);
    when(connections.findByOwnerUserIdOrderByCreatedAtAsc("u1")).thenReturn(List.of());

    var view = withLegacyKeyFile.list();

    assertTrue(
        view.builtIns().stream().noneMatch(item -> "legacy-deepseek".equals(item.id())),
        "即使宿主机存在旧密钥文件，普通用户也不应看到宿主机全局密钥条目");
    assertTrue(view.builtIns().stream().anyMatch(item -> "mock".equals(item.id())));
    verify(auth).initialized();
  }

  @Test
  void listShowsLegacyEntryToAdminWhenKeyFileExists() {
    byte[] key = new byte[32];
    new SecureRandom(new byte[] {4, 2, 6}).nextBytes(key);
    var withLegacyKeyFile =
        new ModelConnectionService(
            connections, probe, auth, new SecretCipher(key), OutboundUrlGuard.strict(), () -> true);
    login("ADMIN", false);
    when(connections.findByOwnerUserIdOrderByCreatedAtAsc("u1")).thenReturn(List.of());

    var view = withLegacyKeyFile.list();

    assertTrue(
        view.builtIns().stream().anyMatch(item -> "legacy-deepseek".equals(item.id())),
        "宿主机存在旧密钥文件时，管理员应能看到遗留兜底条目");
  }

  @Test
  void listOmitsLegacyEntryWhenKeyFileAbsentEvenForAdmin() {
    byte[] key = new byte[32];
    new SecureRandom(new byte[] {4, 2, 6}).nextBytes(key);
    var withoutLegacyKeyFile =
        new ModelConnectionService(
            connections,
            probe,
            auth,
            new SecretCipher(key),
            OutboundUrlGuard.strict(),
            () -> false);
    UserEntity admin = mock(UserEntity.class);
    when(admin.getId()).thenReturn("u1");
    when(auth.current()).thenReturn(Optional.of(admin));
    when(connections.findByOwnerUserIdOrderByCreatedAtAsc("u1")).thenReturn(List.of());

    var view = withoutLegacyKeyFile.list();

    assertTrue(
        view.builtIns().stream().noneMatch(item -> "legacy-deepseek".equals(item.id())),
        "宿主机未放置旧密钥文件时，任何角色都不应看到遗留兜底条目");
    assertTrue(view.builtIns().stream().anyMatch(item -> "mock".equals(item.id())));
  }
}
