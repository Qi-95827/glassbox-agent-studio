package dev.workbench.auth;

import dev.workbench.planning.ExecutionScope;
import dev.workbench.persistence.entity.UserEntity;
import dev.workbench.persistence.repository.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 本地账号、密码摘要和服务端会话。
 *
 * <p>用户身份只来自已认证的 HTTP 会话；请求体、Header 或模型输出中的用户字段不能决定归属。 初始化完成前保留单机本地模式，便于迁移旧数据；初始化后未登录请求由过滤器拒绝。
 */
@Service
public class AuthService {
  public static final String SESSION_USER = "workbench.user.id";
  private static final int ITERATIONS = 120_000;
  private static final int KEY_BITS = 256;
  private static final SecureRandom RANDOM = new SecureRandom();

  private final UserRepository users;

  public AuthService(UserRepository users) {
    this.users = users;
  }

  public record UserView(
      String id,
      String username,
      String displayName,
      String role,
      boolean legacyClaimed,
      String createdAt) {}

  public record Status(boolean initialized, UserView currentUser) {}

  public record InitializeRequest(
      String username,
      String password,
      String displayName,
      boolean claimLegacyHistory) {}

  public record LoginRequest(String username, String password) {}

  public record AddUserRequest(String username, String password, String displayName, String role) {}

  public Status status() {
    return new Status(initialized(), current().map(this::view).orElse(null));
  }

  public boolean initialized() {
    return users.anyUser();
  }

  /** 从服务端会话解析当前用户；没有请求上下文时返回空，不能回退到请求体。 */
  public Optional<UserEntity> current() {
    var attributes = RequestContextHolder.getRequestAttributes();
    if (!(attributes instanceof ServletRequestAttributes servlet)) return Optional.empty();
    HttpSession session = servlet.getRequest().getSession(false);
    if (session == null) return Optional.empty();
    Object value = session.getAttribute(SESSION_USER);
    if (!(value instanceof String id)) return Optional.empty();
    return users.findById(id).filter(user -> "ACTIVE".equals(user.getStatus()));
  }

  public UserEntity requireCurrent() {
    return current().orElseThrow(() -> new AuthRequiredException("请先登录。"));
  }

  public UserEntity requireAdmin() {
    UserEntity user = requireCurrent();
    if (!"ADMIN".equals(user.getRole())) throw new AccessDeniedException("该操作只允许管理员执行。");
    return user;
  }

  /** 首次初始化只能执行一次；管理员可选择显式领取旧 LOCAL 历史数据。 */
  @Transactional
  public synchronized Status initialize(InitializeRequest request, HttpServletRequest http) {
    if (initialized()) throw new IllegalStateException("系统已经初始化，请直接登录。");
    validateUsername(request.username());
    validatePassword(request.password());
    String displayName =
        request.displayName() == null || request.displayName().isBlank()
            ? request.username()
            : request.displayName().strip();
    if (displayName.length() > 120) throw new IllegalArgumentException("显示名称超过 120 字。");
    String id = dev.workbench.task.RunRecordService.id();
    UserEntity admin =
        new UserEntity(
            id,
            request.username().strip().toLowerCase(Locale.ROOT),
            displayName,
            hash(request.password()),
            "ADMIN",
            "ACTIVE",
            request.claimLegacyHistory(),
            dev.workbench.task.RunRecordService.now());
    users.saveAndFlush(admin);
    loginSession(http, admin);
    return new Status(true, view(admin));
  }

  /** 用户名密码登录；失败不区分账号不存在与密码错误。 */
  @Transactional(readOnly = true)
  public UserView login(LoginRequest request, HttpServletRequest http) {
    if (request == null || request.username() == null || request.password() == null)
      throw new IllegalArgumentException("请输入账号和密码。");
    var user =
        users
            .findByUsername(request.username().strip().toLowerCase(Locale.ROOT))
            .filter(item -> "ACTIVE".equals(item.getStatus()))
            .filter(item -> verify(request.password(), item.getPasswordHash()))
            .orElseThrow(() -> new InvalidCredentialsException("账号或密码不正确。"));
    loginSession(http, user);
    return view(user);
  }

  public void logout(HttpServletRequest http) {
    HttpSession session = http.getSession(false);
    if (session != null) session.invalidate();
  }

  @Transactional(readOnly = true)
  public List<UserView> listUsers() {
    requireAdmin();
    return users.findAllByOrderByCreatedAtAsc().stream().map(this::view).toList();
  }

  /** 管理员添加普通用户或另一个管理员；密码只返回一次输入结果的状态，不回显摘要。 */
  @Transactional
  public UserView addUser(AddUserRequest request) {
    requireAdmin();
    validateUsername(request.username());
    validatePassword(request.password());
    String username = request.username().strip().toLowerCase(Locale.ROOT);
    if (users.existsByUsername(username)) throw new IllegalArgumentException("账号已存在。");
    String role = request.role() == null || request.role().isBlank() ? "USER" : request.role();
    if (!List.of("USER", "ADMIN").contains(role)) throw new IllegalArgumentException("账号角色无效。");
    String displayName =
        request.displayName() == null || request.displayName().isBlank()
            ? username
            : request.displayName().strip();
    if (displayName.length() > 120) throw new IllegalArgumentException("显示名称超过 120 字。");
    var saved =
        users.saveAndFlush(
            new UserEntity(
                dev.workbench.task.RunRecordService.id(),
                username,
                displayName,
                hash(request.password()),
                role,
                "ACTIVE",
                false,
                dev.workbench.task.RunRecordService.now()));
    return view(saved);
  }

  /** 管理员显式把旧 LOCAL 记录归入自己的账号；只改变可见性，不改写历史主键。 */
  @Transactional
  public UserView claimLegacyHistory() {
    UserEntity admin = requireAdmin();
    admin.setLegacyClaimed(true);
    return view(users.saveAndFlush(admin));
  }

  /** 当前用户是否可读取旧 LOCAL 数据；初始化前允许单机本地访问。 */
  public boolean canReadLegacy() {
    if (!initialized()) return true;
    return current().map(UserEntity::isLegacyClaimed).orElse(false);
  }

  public ExecutionScope scope() {
    return current()
        .map(
            user -> {
              String key = user.getId().replace("-", "").toUpperCase(Locale.ROOT);
              return new ExecutionScope("USER_" + key, "USER_" + key);
            })
        .orElse(ExecutionScope.LOCAL);
  }

  public Map<String, Object> userMap(UserEntity user) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("id", user.getId());
    result.put("username", user.getUsername());
    result.put("displayName", user.getDisplayName());
    result.put("role", user.getRole());
    result.put("legacyClaimed", user.isLegacyClaimed());
    result.put("createdAt", user.getCreatedAt());
    return result;
  }

  private void loginSession(HttpServletRequest http, UserEntity user) {
    HttpSession session = http.getSession(true);
    http.changeSessionId();
    session.setAttribute(SESSION_USER, user.getId());
  }

  private UserView view(UserEntity user) {
    return new UserView(
        user.getId(),
        user.getUsername(),
        user.getDisplayName(),
        user.getRole(),
        user.isLegacyClaimed(),
        user.getCreatedAt());
  }

  private static void validateUsername(String username) {
    if (username == null || !username.strip().toLowerCase(Locale.ROOT).matches("[a-z0-9][a-z0-9._-]{2,31}"))
      throw new IllegalArgumentException("账号须为 3 至 32 位小写字母、数字、点、下划线或连字符。");
  }

  private static void validatePassword(String password) {
    if (password == null || password.length() < 8 || password.length() > 128)
      throw new IllegalArgumentException("密码须为 8 至 128 位。");
  }

  static String hash(String password) {
    byte[] salt = new byte[16];
    RANDOM.nextBytes(salt);
    byte[] derived = derive(password, salt, ITERATIONS);
    return "pbkdf2$"
        + ITERATIONS
        + "$"
        + Base64.getEncoder().encodeToString(salt)
        + "$"
        + Base64.getEncoder().encodeToString(derived);
  }

  static boolean verify(String password, String encoded) {
    try {
      String[] parts = encoded.split("\\$");
      if (parts.length != 4 || !"pbkdf2".equals(parts[0])) return false;
      int iterations = Integer.parseInt(parts[1]);
      byte[] salt = Base64.getDecoder().decode(parts[2]);
      byte[] expected = Base64.getDecoder().decode(parts[3]);
      byte[] actual = derive(password, salt, iterations);
      return MessageDigest.isEqual(expected, actual);
    } catch (RuntimeException e) {
      return false;
    }
  }

  private static byte[] derive(String password, byte[] salt, int iterations) {
    try {
      var factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
      return factory
          .generateSecret(new PBEKeySpec(password.toCharArray(), salt, iterations, KEY_BITS))
          .getEncoded();
    } catch (Exception e) {
      throw new IllegalStateException("无法计算密码摘要。", e);
    }
  }

  /** 未登录；HTTP 过滤器转换为 401。 */
  public static class AuthRequiredException extends RuntimeException {
    public AuthRequiredException(String message) {
      super(message);
    }
  }

  /** 越权；HTTP 层转换为 403，不泄露目标是否存在。 */
  public static class AccessDeniedException extends RuntimeException {
    public AccessDeniedException(String message) {
      super(message);
    }
  }

  public static class InvalidCredentialsException extends RuntimeException {
    public InvalidCredentialsException(String message) {
      super(message);
    }
  }

  /** 旧数据归属判断使用的字符编码与 ExecutionScope 一致。 */
  public static boolean validScopeKey(String key) {
    return key != null && key.matches("[A-Z0-9_-]{1,80}");
  }

  static String sha256(String text) {
    try {
      return java.util.HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(text.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
