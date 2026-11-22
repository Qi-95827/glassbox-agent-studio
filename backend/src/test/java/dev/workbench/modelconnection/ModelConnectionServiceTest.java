package dev.workbench.modelconnection;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import dev.workbench.auth.AuthService;
import dev.workbench.framework.secret.SecretCipher;
import dev.workbench.persistence.entity.UserModelConnectionEntity;
import dev.workbench.persistence.entity.UserEntity;
import dev.workbench.persistence.repository.UserModelConnectionRepository;
import java.security.SecureRandom;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.EmptyResultDataAccessException;

@ExtendWith(MockitoExtension.class)
class ModelConnectionServiceTest {
  @Mock UserModelConnectionRepository repository;
  @Mock ModelProbe probe;
  @Mock AuthService auth;

  private SecretCipher cipher;
  private ModelConnectionService service;

  @BeforeEach
  void setUp() {
    byte[] key = new byte[32];
    new SecureRandom(new byte[] {9, 8, 7}).nextBytes(key);
    cipher = new SecretCipher(key);
    service = new ModelConnectionService(repository, probe, auth, cipher);
  }

  private void login(String id) {
    UserEntity user = mock(UserEntity.class);
    when(user.getId()).thenReturn(id);
    when(auth.current()).thenReturn(Optional.of(user));
  }

  private static ModelConnectionService.SaveRequest request(String key) {
    return new ModelConnectionService.SaveRequest(
        "我的 DeepSeek",
        "DEEPSEEK",
        "https://api.deepseek.com",
        "deepseek-chat",
        key,
        true,
        true,
        null,
        null,
        "CNY");
  }

  @Test
  void createEncryptsKeyAndReturnsOnlyMask() {
    login("user-a");
    when(repository.existsByOwnerUserId("user-a")).thenReturn(false);
    when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

    var view = service.create(request("sk-12345678903f9a"));

    ArgumentCaptor<UserModelConnectionEntity> saved =
        ArgumentCaptor.forClass(UserModelConnectionEntity.class);
    verify(repository).save(saved.capture());
    assertNotEquals("sk-12345678903f9a", saved.getValue().getEncryptedApiKey());
    assertEquals(
        "sk-12345678903f9a", cipher.decrypt(saved.getValue().getEncryptedApiKey()));
    assertEquals("sk-****3f9a", view.keyMasked());
    assertFalse(view.toString().contains("sk-12345678903f9a"));
    assertTrue(view.isDefault(), "第一个连接自动设为默认");
  }

  @Test
  void updateWithBlankKeyKeepsPreviousCiphertext() {
    login("user-a");
    String encrypted = cipher.encrypt("sk-existing-key-9999");
    UserModelConnectionEntity row =
        new UserModelConnectionEntity(
            "c1",
            "user-a",
            "旧连接",
            "DEEPSEEK",
            "https://api.deepseek.com",
            "deepseek-chat",
            encrypted,
            "sk-****9999",
            true,
            true,
            null,
            null,
            "CNY",
            "t1",
            "t1");
    when(repository.findByIdAndOwnerUserId("c1", "user-a")).thenReturn(Optional.of(row));
    when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

    var view = service.update("c1", request(""));
    assertEquals(encrypted, row.getEncryptedApiKey());
    assertEquals("sk-****9999", view.keyMasked());
    assertEquals("deepseek-chat", view.modelName());
  }

  @Test
  void otherUsersCannotReadOrDeleteByKnowingId() {
    login("user-b");
    when(repository.findByIdAndOwnerUserId("c1", "user-b")).thenReturn(Optional.empty());

    assertThrows(EmptyResultDataAccessException.class, () -> service.delete("c1"));
    assertThrows(EmptyResultDataAccessException.class, () -> service.setDefault("c1"));
    assertThrows(EmptyResultDataAccessException.class, () -> service.test("c1"));
    assertThrows(EmptyResultDataAccessException.class, () -> service.requireEnabledOwned("c1"));
    verify(repository, never()).delete(any());
  }

  @Test
  void disabledConnectionCannotBeUsedByTaskCreation() {
    login("user-a");
    UserModelConnectionEntity row =
        new UserModelConnectionEntity(
            "c1",
            "user-a",
            "停用连接",
            "OPENAI",
            "https://api.openai.com/v1",
            "gpt-4o-mini",
            cipher.encrypt("sk-x"),
            "sk-****",
            false,
            true,
            null,
            null,
            "CNY",
            "t1",
            "t1");
    when(repository.findByIdAndOwnerUserId("c1", "user-a")).thenReturn(Optional.of(row));
    assertThrows(IllegalStateException.class, () -> service.requireEnabledOwned("c1"));
  }

  @Test
  void draftTestUsesProvidedKeyWithoutPersisting() {
    ModelProbe.ProbeResult ok = new ModelProbe.ProbeResult(true, "已连通");
    when(probe.probe(any())).thenReturn(ok);

    var result =
        service.testDraft(
            new ModelConnectionService.TestDraftRequest(
                null,
                "OPENAI",
                "https://api.openai.com/v1",
                "gpt-4o-mini",
                "sk-draft-key"));

    assertTrue(result.ok());
    assertEquals("OK", result.status());
    verify(repository, never()).save(any());
    ArgumentCaptor<RuntimeModelConnection> runtime =
        ArgumentCaptor.forClass(RuntimeModelConnection.class);
    verify(probe).probe(runtime.capture());
    assertEquals("sk-draft-key", runtime.getValue().apiKey());
  }

  @Test
  void rejectsUnsafeBaseUrls() {
    assertThrows(
        IllegalArgumentException.class,
        () -> service.testDraft(requestDraft("http://example.com/v1")));
    assertThrows(
        IllegalArgumentException.class,
        () -> service.testDraft(requestDraft("http://10.0.0.8/v1")));
    assertThrows(
        IllegalArgumentException.class,
        () -> service.testDraft(requestDraft("ftp://example.com")));
  }

  private static ModelConnectionService.TestDraftRequest requestDraft(String baseUrl) {
    return new ModelConnectionService.TestDraftRequest(
        null, "CUSTOM_OPENAI", baseUrl, "gpt-4o-mini", "sk-draft");
  }

  @Test
  void listShowsOnlyCurrentUsersConnections() {
    login("user-a");
    when(repository.findByOwnerUserIdOrderByCreatedAtAsc("user-a")).thenReturn(List.of());

    var view = service.list();
    assertTrue(view.connections().isEmpty());
    assertTrue(view.builtIns().stream().anyMatch(item -> item.id().equals("mock")));
    assertTrue(view.presets().stream().anyMatch(item -> item.providerType().equals("DEEPSEEK")));
  }
}
