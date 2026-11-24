package dev.workbench.modelconnection;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.workbench.auth.AuthService;
import dev.workbench.framework.outbound.OutboundUrlGuard;
import dev.workbench.framework.secret.SecretCipher;
import dev.workbench.persistence.repository.UserModelConnectionRepository;
import java.net.InetAddress;
import java.security.SecureRandom;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * BYOK Base URL 的出站安全校验：保存阶段就必须拦住本机、内网、云元数据与非标准 IP 写法。
 *
 * <p>这些地址一旦被接受，服务端会用管理员或他人的 API Key 发起请求，构成 SSRF 与密钥外带。
 */
@ExtendWith(MockitoExtension.class)
class ModelConnectionSecurityTest {
  @Mock UserModelConnectionRepository repository;
  @Mock ModelProbe probe;
  @Mock AuthService auth;

  private SecretCipher cipher;

  @BeforeEach
  void setUp() {
    byte[] key = new byte[32];
    new SecureRandom(new byte[] {1, 2, 3}).nextBytes(key);
    cipher = new SecretCipher(key);
  }

  private ModelConnectionService service(OutboundUrlGuard guard) {
    return new ModelConnectionService(repository, probe, auth, cipher, guard);
  }

  private ModelConnectionService strictService() {
    return service(OutboundUrlGuard.strict());
  }

  private static ModelConnectionService.TestDraftRequest draft(String baseUrl) {
    return new ModelConnectionService.TestDraftRequest(
        null, "CUSTOM_OPENAI", baseUrl, "gpt-4o-mini", "sk-draft-key");
  }

  private void assertRejected(ModelConnectionService service, String baseUrl) {
    var error =
        assertThrows(
            IllegalArgumentException.class, () -> service.testDraft(draft(baseUrl)), baseUrl);
    assertFalse(
        error.getMessage() == null || error.getMessage().isBlank(), "拒绝时必须给出可读的受控提示：" + baseUrl);
    assertFalse(error.getMessage().contains("sk-draft-key"), "错误信息不得回显密钥");
    verify(probe, never()).probe(any());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "https://[::1]/v1",
        "https://[::ffff:169.254.169.254]/v1",
        "https://[fc00::1]/v1",
        "https://169.254.169.254/latest/meta-data",
        "https://10.0.0.8/v1",
        "https://192.168.1.10/v1",
        "https://100.64.0.1/v1",
        "https://2852039166/v1",
        "http://api.deepseek.com/v1",
        "https://api.deepseek.com/v1?redirect=http://127.0.0.1",
        "https://user:pass@api.deepseek.com/v1",
        "ftp://api.deepseek.com"
      })
  void rejectsUnsafeBaseUrls(String baseUrl) {
    assertRejected(strictService(), baseUrl);
  }

  @Test
  void rejectsDomainResolvingToMetadataAddress() {
    OutboundUrlGuard.HostResolver metadata =
        host -> new InetAddress[] {InetAddress.getByName("169.254.169.254")};
    assertRejected(
        service(new OutboundUrlGuard(false, metadata)), "https://169.254.169.254.nip.io/v1");
  }

  @Test
  void acceptsPublicHttpsEndpoint() {
    when(probe.probe(any())).thenReturn(new ModelProbe.ProbeResult(true, "已连通"));
    var result = strictService().testDraft(draft("https://api.deepseek.com/v1"));
    assertTrue(result.ok());
    verify(probe).probe(any());
  }

  @Test
  void loopbackHttpRequiresExplicitSwitch() {
    assertRejected(strictService(), "http://127.0.0.1:11434/v1");
    when(probe.probe(any())).thenReturn(new ModelProbe.ProbeResult(true, "已连通"));
    var result =
        service(OutboundUrlGuard.allowLocalLoopback())
            .testDraft(draft("http://127.0.0.1:11434/v1"));
    assertTrue(result.ok());
  }

  @Test
  void loopbackSwitchStillRejectsPrivateNetworkAddresses() {
    var service = service(OutboundUrlGuard.allowLocalLoopback());
    assertRejected(service, "http://192.168.1.5:8080/v1");
    assertRejected(service, "https://10.1.2.3/v1");
  }

  @Test
  void probeFailuresAreMaskedBeforeReturningToCaller() {
    when(probe.probe(any()))
        .thenReturn(
            new ModelProbe.ProbeResult(
                false, "401 sk-live-abcdefghijklmnop Bearer abcdefghijklmnop"));
    var result = strictService().testDraft(draft("https://api.deepseek.com/v1"));
    assertFalse(result.ok());
    assertFalse(result.message().contains("sk-live-abcdefghijklmnop"), result.message());
    assertFalse(result.message().contains("abcdefghijklmnop"), result.message());
  }

  @Test
  void blankBaseUrlIsRejectedBeforeAnyRequest() {
    assertRejected(strictService(), "");
    assertRejected(strictService(), "   ");
  }

  @Test
  void loginIsNotRequiredForDraftProbeButOwnershipIs() {
    // 草稿探活不读取连接归属；一旦携带 connectionId，必须走 requireOwned（他人连接返回 404 语义）。
    when(repository.findByIdAndOwnerUserId("c-other", "LOCAL")).thenReturn(Optional.empty());
    assertThrows(
        org.springframework.dao.EmptyResultDataAccessException.class,
        () ->
            strictService()
                .testDraft(
                    new ModelConnectionService.TestDraftRequest(
                        "c-other", "OPENAI", "https://api.openai.com/v1", "gpt-4o-mini", "sk-x")));
  }
}
