package dev.workbench.modelconnection;

import dev.workbench.auth.AuthService;
import dev.workbench.domain.WorkbenchIds;
import dev.workbench.domain.WorkbenchTime;
import dev.workbench.framework.outbound.OutboundUrlGuard;
import dev.workbench.framework.secret.ApiKeyMasker;
import dev.workbench.framework.secret.SecretCipher;
import dev.workbench.framework.trace.SensitiveDataMasker;
import dev.workbench.persistence.entity.UserModelConnectionEntity;
import dev.workbench.persistence.repository.UserModelConnectionRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 用户自带模型连接（BYOK）配置。
 *
 * <p>密钥只以 AES-256-GCM 密文落库，任何返回值只含掩码。任务创建、想法整理与计划生成在发起 模型调用前校验连接归属与启用状态；运行时按连接标识解密一次，用完即丢。
 */
@Service
public class ModelConnectionService {
  private static final int MESSAGE_LIMIT = 500;
  private static final Path LEGACY_DEEPSEEK_KEY = Path.of("config", "deepseek-api-key.txt");

  private final UserModelConnectionRepository connections;
  private final SecretCipher cipher;
  private final ModelProbe probe;
  private final AuthService auth;
  private final OutboundUrlGuard guard;

  @org.springframework.beans.factory.annotation.Autowired
  public ModelConnectionService(
      UserModelConnectionRepository connections,
      ModelProbe probe,
      AuthService auth,
      OutboundUrlGuard guard) {
    this(connections, probe, auth, SecretCipher.shared(), guard);
  }

  /** 单元测试可注入固定主密钥，避免依赖本机 config/master.key。 */
  ModelConnectionService(
      UserModelConnectionRepository connections,
      ModelProbe probe,
      AuthService auth,
      SecretCipher cipher) {
    this(connections, probe, auth, cipher, OutboundUrlGuard.strict());
  }

  /** 单元测试可同时替换出站校验策略，覆盖回环开关与 DNS 结果。 */
  ModelConnectionService(
      UserModelConnectionRepository connections,
      ModelProbe probe,
      AuthService auth,
      SecretCipher cipher,
      OutboundUrlGuard guard) {
    this.connections = connections;
    this.probe = probe;
    this.auth = auth;
    this.cipher = cipher;
    this.guard = guard == null ? OutboundUrlGuard.strict() : guard;
  }

  public record SaveRequest(
      String displayName,
      String providerType,
      String baseUrl,
      String modelName,
      String apiKey,
      Boolean enabled,
      Boolean isDefault,
      Double inputPricePer1m,
      Double outputPricePer1m,
      String currency) {}

  public record TestDraftRequest(
      String connectionId, String providerType, String baseUrl, String modelName, String apiKey) {}

  public record ConnectionView(
      String id,
      String displayName,
      String providerType,
      String providerLabel,
      String baseUrl,
      String modelName,
      String keyMasked,
      boolean enabled,
      boolean isDefault,
      Double inputPricePer1m,
      Double outputPricePer1m,
      String currency,
      String lastTestStatus,
      String lastTestMessage,
      String lastTestedAt,
      String createdAt,
      String updatedAt,
      boolean builtIn,
      boolean readOnly,
      String note) {}

  public record PresetView(
      String providerType,
      String label,
      String defaultBaseUrl,
      List<String> models,
      String docsUrl) {}

  public record ListView(
      List<ConnectionView> connections, List<ConnectionView> builtIns, List<PresetView> presets) {}

  public record TestResult(
      boolean ok, String status, long latencyMs, String message, String testedAt) {}

  @Transactional(readOnly = true)
  public List<PresetView> presets() {
    return ModelProviderType.presets().stream()
        .map(
            type ->
                new PresetView(
                    type.name(),
                    type.label(),
                    type.defaultBaseUrl(),
                    type.models(),
                    type.docsUrl()))
        .toList();
  }

  @Transactional(readOnly = true)
  public ListView list() {
    String owner = ownerId();
    List<ConnectionView> mine =
        connections.findByOwnerUserIdOrderByCreatedAtAsc(owner).stream()
            .map(ModelConnectionService::view)
            .toList();
    List<ConnectionView> builtIns = new ArrayList<>();
    builtIns.add(
        new ConnectionView(
            "mock",
            "模拟模型（免 Key）",
            "MOCK",
            "内置模拟",
            "",
            "mock",
            "",
            true,
            false,
            null,
            null,
            "CNY",
            null,
            null,
            null,
            null,
            null,
            true,
            true,
            "用于教学和快速体验，不产生真实模型费用。"));
    if (Files.exists(LEGACY_DEEPSEEK_KEY) && legacyKeyAllowed()) {
      builtIns.add(
          new ConnectionView(
              "legacy-deepseek",
              "本地 DeepSeek 密钥文件",
              ModelProviderType.DEEPSEEK.name(),
              ModelProviderType.DEEPSEEK.label(),
              ModelProviderType.DEEPSEEK.defaultBaseUrl(),
              "deepseek-flash",
              "文件内密钥已隐藏",
              true,
              false,
              null,
              null,
              "CNY",
              null,
              null,
              null,
              null,
              null,
              true,
              true,
              "检测到 config/deepseek-api-key.txt，保留为只读兜底；建议改用页面配置。"));
    }
    return new ListView(mine, builtIns, presets());
  }

  @Transactional
  public ConnectionView create(SaveRequest request) {
    String owner = ownerId();
    Input input = validate(request, true, null);
    boolean first = !connections.existsByOwnerUserId(owner);
    boolean makeDefault = first || Boolean.TRUE.equals(request.isDefault());
    String now = WorkbenchTime.now();
    if (makeDefault) connections.clearDefault(owner, now);
    var saved =
        connections.save(
            new UserModelConnectionEntity(
                WorkbenchIds.newId(),
                owner,
                input.displayName(),
                input.provider().name(),
                input.baseUrl(),
                input.modelName(),
                cipher.encrypt(input.apiKey()),
                ApiKeyMasker.mask(input.apiKey()),
                input.enabled(),
                makeDefault,
                input.inputPrice(),
                input.outputPrice(),
                input.currency(),
                now,
                now));
    return view(saved);
  }

  @Transactional
  public ConnectionView update(String id, SaveRequest request) {
    String owner = ownerId();
    var row = requireOwned(id, owner);
    Input input = validate(request, false, row);
    String now = WorkbenchTime.now();
    boolean makeDefault = request.isDefault() == null ? row.isDefaultFlag() : request.isDefault();
    if (makeDefault && !row.isDefaultFlag()) connections.clearDefault(owner, now);
    row.setDisplayName(input.displayName());
    row.setProviderType(input.provider().name());
    row.setBaseUrl(input.baseUrl());
    row.setModelName(input.modelName());
    row.setInputPricePer1m(input.inputPrice());
    row.setOutputPricePer1m(input.outputPrice());
    row.setCurrency(input.currency());
    row.setEnabled(input.enabled());
    row.setDefaultFlag(makeDefault);
    if (input.apiKey() != null) {
      row.setEncryptedApiKey(cipher.encrypt(input.apiKey()));
      row.setKeyMasked(ApiKeyMasker.mask(input.apiKey()));
    }
    row.setUpdatedAt(now);
    return view(connections.save(row));
  }

  @Transactional
  public void delete(String id) {
    String owner = ownerId();
    var row = requireOwned(id, owner);
    connections.delete(row);
    if (row.isDefaultFlag()) {
      connections.findByOwnerUserIdOrderByCreatedAtAsc(owner).stream()
          .filter(UserModelConnectionEntity::isEnabled)
          .findFirst()
          .ifPresent(
              next -> {
                next.setDefaultFlag(true);
                next.setUpdatedAt(WorkbenchTime.now());
                connections.save(next);
              });
    }
  }

  @Transactional
  public ConnectionView setDefault(String id) {
    String owner = ownerId();
    var row = requireOwned(id, owner);
    if (!row.isEnabled())
      throw new IllegalStateException(
          dev.workbench.framework.i18n.LocalizedMessages.get("modelConnection.disabled"));
    String now = WorkbenchTime.now();
    connections.clearDefault(owner, now);
    row.setDefaultFlag(true);
    row.setUpdatedAt(now);
    return view(connections.save(row));
  }

  @Transactional
  public TestResult test(String id) {
    var row = requireOwned(id, ownerId());
    RuntimeModelConnection runtime = runtime(row);
    TestResult result = runProbe(runtime);
    row.setLastTest(result.ok() ? "OK" : "FAILED", result.message(), result.testedAt());
    row.setUpdatedAt(result.testedAt());
    connections.save(row);
    return result;
  }

  /** 未保存草稿测试；携带 connectionId 且密钥留空时复用已保存的密文。 */
  public TestResult testDraft(TestDraftRequest request) {
    RuntimeModelConnection runtime;
    if (request.connectionId() == null || request.connectionId().isBlank()) {
      String provider = requiredProvider(request.providerType()).name();
      String apiKey = validateApiKey(request.apiKey());
      runtime =
          new RuntimeModelConnection(
              "draft",
              "草稿连接",
              ModelProviderType.fromCode(provider),
              validateBaseUrl(request.baseUrl()),
              validateModelName(request.modelName()),
              apiKey,
              null,
              null,
              "CNY");
    } else {
      var row = requireOwned(request.connectionId(), ownerId());
      runtime =
          new RuntimeModelConnection(
              row.getId(),
              row.getDisplayName(),
              ModelProviderType.fromCode(
                  request.providerType() == null || request.providerType().isBlank()
                      ? row.getProviderType()
                      : request.providerType()),
              validateBaseUrl(
                  request.baseUrl() == null || request.baseUrl().isBlank()
                      ? row.getBaseUrl()
                      : request.baseUrl()),
              validateModelName(
                  request.modelName() == null || request.modelName().isBlank()
                      ? row.getModelName()
                      : request.modelName()),
              request.apiKey() == null || request.apiKey().isBlank()
                  ? cipher.decrypt(row.getEncryptedApiKey())
                  : validateApiKey(request.apiKey()),
              row.getInputPricePer1m(),
              row.getOutputPricePer1m(),
              row.getCurrency());
    }
    return runProbe(runtime);
  }

  /** 任务创建入口：只接受当前登录用户且已启用的连接。 */
  @Transactional(readOnly = true)
  public String requireEnabledOwned(String id) {
    var row = requireOwned(id, ownerId());
    if (!row.isEnabled())
      throw new IllegalStateException(
          dev.workbench.framework.i18n.LocalizedMessages.get("modelConnection.disabled"));
    return row.getId();
  }

  /** 未显式选择模型时回退到当前用户的默认连接。 */
  @Transactional(readOnly = true)
  public Optional<String> defaultConnectionId() {
    return connections
        .findFirstByOwnerUserIdAndDefaultFlagTrueAndEnabledTrueOrderByCreatedAtAsc(ownerId())
        .map(UserModelConnectionEntity::getId);
  }

  /**
   * 当前操作人是否可以使用宿主机遗留明文密钥 {@code config/deepseek-api-key.txt}。
   *
   * <p>该文件是全平台唯一的全局凭据：多用户模式下普通用户借它发起模型调用，等于白用管理员的额度， 且请求内容会发往与本人无关的账号。只有管理员或已显式领取历史数据的账号可以使用；
   * 尚未初始化账号的单机本地模式保持历史行为。
   */
  @Transactional(readOnly = true)
  public boolean legacyKeyAllowed() {
    if (!auth.initialized()) return true;
    return auth.current()
        .map(user -> "ADMIN".equals(user.getRole()) || user.isLegacyClaimed())
        .orElse(false);
  }

  /** 即将使用宿主机全局模型时调用；无权限则直接拒绝并给出可操作提示。 */
  public void requireLegacyKeyAccess() {
    if (legacyKeyAllowed()) return;
    throw new AuthService.AccessDeniedException("宿主机全局 DeepSeek 密钥仅限管理员使用，请先在设置中配置并启用你自己的模型连接。");
  }

  /** 运行时解析：连接标识来自已校验并冻结的任务，不再读取浏览器会话。 */
  @Transactional(readOnly = true)
  public RuntimeModelConnection resolveForExecution(String id) {
    if (id == null || id.isBlank())
      throw new IllegalStateException(
          dev.workbench.framework.i18n.LocalizedMessages.get("modelConnection.idMissing"));
    var row =
        connections
            .findById(id)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        dev.workbench.framework.i18n.LocalizedMessages.get(
                            "modelConnection.notFound")));
    if (!row.isEnabled())
      throw new IllegalStateException(
          dev.workbench.framework.i18n.LocalizedMessages.get("modelConnection.disabled"));
    return runtime(row);
  }

  private RuntimeModelConnection runtime(UserModelConnectionEntity row) {
    return new RuntimeModelConnection(
        row.getId(),
        row.getDisplayName(),
        ModelProviderType.fromCode(row.getProviderType()),
        row.getBaseUrl(),
        row.getModelName(),
        cipher.decrypt(row.getEncryptedApiKey()),
        row.getInputPricePer1m(),
        row.getOutputPricePer1m(),
        row.getCurrency());
  }

  private TestResult runProbe(RuntimeModelConnection runtime) {
    long started = System.nanoTime();
    ModelProbe.ProbeResult result = probe.probe(runtime);
    long latency = Math.max(0, (System.nanoTime() - started) / 1_000_000);
    String message = SensitiveDataMasker.maskAndTruncate(result.message(), MESSAGE_LIMIT).text();
    return new TestResult(
        result.ok(), result.ok() ? "OK" : "FAILED", latency, message, WorkbenchTime.now());
  }

  private UserModelConnectionEntity requireOwned(String id, String owner) {
    return connections
        .findByIdAndOwnerUserId(id, owner)
        .orElseThrow(() -> new EmptyResultDataAccessException(1));
  }

  private String ownerId() {
    return auth.current().map(user -> user.getId()).orElse("LOCAL");
  }

  private static ConnectionView view(UserModelConnectionEntity row) {
    ModelProviderType type;
    try {
      type = ModelProviderType.fromCode(row.getProviderType());
    } catch (IllegalArgumentException e) {
      type = ModelProviderType.CUSTOM_OPENAI;
    }
    return new ConnectionView(
        row.getId(),
        row.getDisplayName(),
        row.getProviderType(),
        type.label(),
        row.getBaseUrl(),
        row.getModelName(),
        row.getKeyMasked(),
        row.isEnabled(),
        row.isDefaultFlag(),
        row.getInputPricePer1m(),
        row.getOutputPricePer1m(),
        row.getCurrency(),
        row.getLastTestStatus(),
        row.getLastTestMessage(),
        row.getLastTestedAt(),
        row.getCreatedAt(),
        row.getUpdatedAt(),
        false,
        false,
        null);
  }

  private Input validate(
      SaveRequest request, boolean creating, UserModelConnectionEntity existing) {
    if (request == null)
      throw new IllegalArgumentException(
          dev.workbench.framework.i18n.LocalizedMessages.get("modelConnection.missing"));
    ModelProviderType provider = requiredProvider(request.providerType());
    String displayName =
        request.displayName() == null || request.displayName().isBlank()
            ? provider.label() + " · " + safeModel(request.modelName())
            : request.displayName().strip();
    if (displayName.length() > 80)
      throw new IllegalArgumentException(
          dev.workbench.framework.i18n.LocalizedMessages.get("modelConnection.nameTooLong"));
    String modelName = validateModelName(request.modelName());
    String baseUrl = validateBaseUrl(request.baseUrl());
    String apiKey = request.apiKey();
    if (apiKey != null && !apiKey.isBlank()) apiKey = validateApiKey(apiKey);
    else if (creating)
      throw new IllegalArgumentException(
          dev.workbench.framework.i18n.LocalizedMessages.get("modelConnection.apiKeyRequired"));
    else apiKey = null;
    boolean enabled = request.enabled() == null || request.enabled();
    double inputPrice = price(request.inputPricePer1m(), "输入参考单价");
    double outputPrice = price(request.outputPricePer1m(), "输出参考单价");
    String currency =
        request.currency() == null || request.currency().isBlank()
            ? existing == null ? "CNY" : existing.getCurrency()
            : request.currency().strip().toUpperCase(Locale.ROOT);
    if (!List.of("CNY", "USD", "EUR").contains(currency))
      throw new IllegalArgumentException(
          dev.workbench.framework.i18n.LocalizedMessages.get("modelConnection.currency"));
    return new Input(
        displayName,
        provider,
        baseUrl,
        modelName,
        apiKey,
        enabled,
        inputPrice == 0 ? null : inputPrice,
        outputPrice == 0 ? null : outputPrice,
        currency);
  }

  private static double price(Double value, String label) {
    if (value == null) return 0;
    if (value < 0 || value > 1_000_000)
      throw new IllegalArgumentException(
          dev.workbench.framework.i18n.LocalizedMessages.get("modelConnection.price"));
    return value;
  }

  private static ModelProviderType requiredProvider(String code) {
    if (code == null || code.isBlank())
      throw new IllegalArgumentException(
          dev.workbench.framework.i18n.LocalizedMessages.get("modelConnection.providerRequired"));
    return ModelProviderType.fromCode(code);
  }

  private static String validateModelName(String value) {
    if (value == null || !value.strip().matches("[A-Za-z0-9._:/-]{1,120}"))
      throw new IllegalArgumentException(
          dev.workbench.framework.i18n.LocalizedMessages.get("modelConnection.modelNameInvalid"));
    return value.strip();
  }

  private static String safeModel(String value) {
    return value == null || value.isBlank() ? "未命名模型" : value.strip();
  }

  private static String validateApiKey(String value) {
    if (value == null || value.isBlank())
      throw new IllegalArgumentException(
          dev.workbench.framework.i18n.LocalizedMessages.get("modelConnection.apiKeyRequired"));
    String key = value.strip();
    if (key.length() < 4 || key.length() > 1000 || key.contains("\n") || key.contains("\r"))
      throw new IllegalArgumentException(
          dev.workbench.framework.i18n.LocalizedMessages.get("modelConnection.apiKeyInvalid"));
    return key;
  }

  /**
   * 只允许 https 公网地址；http 仅允许本机回环，避免把服务端请求引向内网。
   *
   * <p>具体网段判定统一交给 {@link OutboundUrlGuard}：这里只把受控拒绝原因映射成本模块既有的用户文案，
   * 保证前端提示与历史行为兼容。保存阶段使用宽松校验（域名暂时无法解析时放行），真实出站前由 Embedding / MCP / 模型探测各自再执行一次严格校验。
   */
  private String validateBaseUrl(String value) {
    if (value == null || value.isBlank())
      throw new IllegalArgumentException(
          dev.workbench.framework.i18n.LocalizedMessages.get("modelConnection.baseUrlRequired"));
    String text = value.strip();
    if (text.endsWith("/")) text = text.substring(0, text.length() - 1);
    try {
      return guard.validate(text);
    } catch (OutboundUrlGuard.Rejected rejected) {
      throw new IllegalArgumentException(
          dev.workbench.framework.i18n.LocalizedMessages.get(messageKey(rejected.reason())));
    }
  }

  private static String messageKey(OutboundUrlGuard.Reason reason) {
    return switch (reason) {
      case MISSING -> "modelConnection.baseUrlRequired";
      case CREDENTIALS -> "modelConnection.baseUrlCredentials";
      case QUERY, FRAGMENT -> "modelConnection.baseUrlQuery";
      case SCHEME -> "modelConnection.baseUrlScheme";
      case HOST -> "modelConnection.baseUrlHost";
      case LOOPBACK_HTTP -> "modelConnection.baseUrlLoopback";
      case LOOPBACK_DISABLED -> "modelConnection.baseUrlLoopbackDisabled";
      case PRIVATE -> "modelConnection.baseUrlPrivate";
      case UNRESOLVED -> "modelConnection.baseUrlUnresolved";
      case INVALID -> "modelConnection.baseUrlInvalid";
    };
  }

  private record Input(
      String displayName,
      ModelProviderType provider,
      String baseUrl,
      String modelName,
      String apiKey,
      boolean enabled,
      Double inputPrice,
      Double outputPrice,
      String currency) {}
}
