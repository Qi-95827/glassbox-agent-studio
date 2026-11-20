package dev.workbench.ownership;

import dev.workbench.auth.AuthService;
import dev.workbench.persistence.entity.RepositoryAuthorizationEntity;
import dev.workbench.persistence.entity.UserEntity;
import dev.workbench.persistence.repository.RepositoryAuthorizationRepository;
import dev.workbench.persistence.repository.UserRepository;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 仓库导入授权。
 *
 * <p>工作区 ID 归属只在创建后生效；这里在解析仓库和创建 Git worktree 之前，按当前会话核对规范化 仓库根目录是否已由管理员显式授权给该账号。初始化前的单机模式保持旧行为。
 */
@Service
public class RepositoryAuthorizationService {
  private final AuthService auth;
  private final UserRepository users;
  private final RepositoryAuthorizationRepository authorizations;

  public RepositoryAuthorizationService(
      AuthService auth,
      UserRepository users,
      RepositoryAuthorizationRepository authorizations) {
    this.auth = auth;
    this.users = users;
    this.authorizations = authorizations;
  }

  public record GrantView(
      String id,
      String username,
      String repositoryRoot,
      String grantedBy,
      String createdAt) {}

  /** 未初始化时保留单机导入；初始化后必须存在当前账号的精确仓库授权。 */
  public void requireAuthorized(Path repositoryRoot) {
    if (!auth.initialized()) return;
    var user = auth.requireCurrent();
    String key = repositoryKey(repositoryRoot);
    if (authorizations
        .findByOwnerScopeKeyAndRepositoryKey(scopeKey(user), key)
        .isEmpty())
      throw new AuthService.AccessDeniedException(
          "该仓库尚未授权给当前账号，请联系管理员授权后再导入。");
  }

  @Transactional
  public GrantView grant(String username, String repositoryPath) {
    UserEntity admin = auth.requireAdmin();
    if (username == null || username.isBlank())
      throw new IllegalArgumentException("请选择要授权的账号。");
    UserEntity user =
        users
            .findByUsername(username.strip().toLowerCase(Locale.ROOT))
            .orElseThrow(() -> new IllegalArgumentException("账号不存在。"));
    Path root;
    try {
      root = Path.of(repositoryPath).toRealPath();
    } catch (RuntimeException | java.io.IOException e) {
      throw new IllegalArgumentException("仓库目录不存在或不可访问。");
    }
    if (!Files.isDirectory(root)) throw new IllegalArgumentException("仓库路径不是目录。");
    String key = repositoryKey(root);
    String owner = scopeKey(user);
    var existing = authorizations.findByOwnerScopeKeyAndRepositoryKey(owner, key);
    if (existing.isPresent()) return view(existing.get(), user.getUsername());
    var saved =
        authorizations.saveAndFlush(
            new RepositoryAuthorizationEntity(
                dev.workbench.task.RunRecordService.id(),
                owner,
                root.toString(),
                key,
                admin.getId(),
                dev.workbench.task.RunRecordService.now()));
    return view(saved, user.getUsername());
  }

  @Transactional(readOnly = true)
  public List<GrantView> listCurrent() {
    UserEntity user = auth.requireCurrent();
    return authorizations.findByOwnerScopeKeyOrderByCreatedAtDesc(scopeKey(user)).stream()
        .map(row -> view(row, user.getUsername()))
        .toList();
  }

  @Transactional(readOnly = true)
  public List<GrantView> listAll() {
    auth.requireAdmin();
    var byScope = new java.util.HashMap<String, String>();
    for (var user : users.findAll()) byScope.put(scopeFor(user), user.getUsername());
    return authorizations.findAllByOrderByCreatedAtDesc().stream()
        .map(row -> view(row, byScope.getOrDefault(row.getOwnerScopeKey(), "已删除账号")))
        .toList();
  }

  /** 撤销导入授权：只禁止新导入，已有工作区仍按原归属访问。 */
  @Transactional
  public void revoke(String id) {
    auth.requireAdmin();
    var row =
        authorizations
            .findById(id)
            .orElseThrow(() -> new EmptyResultDataAccessException(1));
    authorizations.delete(row);
  }

  private String scopeKey(UserEntity user) {
    return scopeFor(user);
  }

  private static String scopeFor(UserEntity user) {
    return "USER_" + user.getId().replace("-", "").toUpperCase(Locale.ROOT);
  }

  /** Windows 路径大小写不敏感；摘要键用于唯一约束，展示仍保留真实路径。 */
  public static String repositoryKey(Path repositoryRoot) {
    String normalized;
    try {
      normalized =
          repositoryRoot.toRealPath().toString().replace('\\', '/').toLowerCase(Locale.ROOT);
    } catch (java.io.IOException e) {
      throw new IllegalArgumentException("仓库目录不存在或不可访问。");
    }
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(normalized.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException("无法计算仓库授权键。", e);
    }
  }

  private GrantView view(RepositoryAuthorizationEntity row, String username) {
    String grantor =
        users.findById(row.getGrantedBy()).map(UserEntity::getUsername).orElse(row.getGrantedBy());
    return new GrantView(
        row.getId(), username, row.getRepositoryRoot(), grantor, row.getCreatedAt());
  }
}
