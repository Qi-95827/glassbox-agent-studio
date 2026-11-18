package dev.workbench.workspace;

import dev.workbench.task.RunRepository;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** 个人本地仓库工作区；Git 隔离不等于不可信代码执行沙箱。 */
@Service
public class WorkspaceService {
  private static final int OUTPUT_LIMIT = 1_000_000;
  private final JdbcTemplate db;
  private final Path workspaceRoot;

  @Autowired
  public WorkspaceService(
      JdbcTemplate db, @Value("${workbench.workspace-root:data/workspaces}") String workspaceRoot) {
    this.db = db;
    this.workspaceRoot = Path.of(workspaceRoot).toAbsolutePath().normalize();
  }

  /** 用户显式指定仓库和分支；只允许创建独立工作区，不覆盖当前工作区。 */
  public synchronized Workspace create(String repository, String branch) {
    if (repository == null || branch == null || branch.isBlank() || branch.startsWith("-"))
      throw new IllegalArgumentException("请填写本地仓库目录与基础分支。");
    Path repo;
    try {
      repo = Path.of(repository).toRealPath();
    } catch (IOException | RuntimeException e) {
      throw new IllegalArgumentException("仓库目录不存在或不可访问。");
    }
    String branchRef = "refs/heads/" + branch;
    runGit(repo, "check-ref-format", branchRef);
    String top = runGit(repo, "rev-parse", "--show-toplevel").strip();
    Path root;
    try {
      root = Path.of(top).toRealPath();
    } catch (IOException e) {
      throw new IllegalArgumentException("无法定位仓库根目录。");
    }
    String commit = runGit(root, "rev-parse", "--verify", branchRef + "^{commit}").strip();
    if (!commit.matches("[0-9a-fA-F]{40,64}")) throw new IllegalStateException("无法确定基础提交。");
    String id = RunRepository.id();
    String name = "agent/work-" + id.substring(0, 12);
    Path target = workspaceRoot.resolve(id).normalize();
    if (!target.startsWith(workspaceRoot)) throw new IllegalStateException("工作区路径越界。");
    try {
      Files.createDirectories(workspaceRoot);
    } catch (IOException e) {
      throw new IllegalStateException("无法创建工作区根目录。");
    }
    // 使用独立 worktree 和分支；命令参数分离，且关闭 Git hooks。
    runGit(root, "worktree", "add", "-b", name, "--", target.toString(), commit);
    db.update(
        "INSERT INTO workspaces VALUES(?,?,?,?,?,?,?)",
        id,
        root.toString(),
        target.toString(),
        branch,
        commit,
        name,
        RunRepository.now());
    return get(id);
  }

  public List<Workspace> list() {
    return db.query("SELECT * FROM workspaces ORDER BY created_at DESC", (rs, row) -> map(rs));
  }

  public Workspace get(String id) {
    return db.queryForObject("SELECT * FROM workspaces WHERE id=?", (rs, row) -> map(rs), id);
  }

  private Workspace map(java.sql.ResultSet rs) throws java.sql.SQLException {
    return new Workspace(
        rs.getString("id"),
        rs.getString("repository_path"),
        rs.getString("workspace_path"),
        rs.getString("base_branch"),
        rs.getString("base_commit"),
        rs.getString("branch_name"),
        rs.getString("created_at"));
  }

  /** 显示真实差异和目标分支是否发生变化，不自动合并或推送。 */
  public Status status(String id) {
    Workspace workspace = get(id);
    Path path = Path.of(workspace.workspacePath());
    String changes = runGit(path, "status", "--porcelain=v1", "--untracked-files=all");
    String diff = runGit(path, "diff", "--no-ext-diff", "--no-textconv", "HEAD", "--", ".");
    String currentBase =
        runGit(
                Path.of(workspace.repositoryPath()),
                "rev-parse",
                "--verify",
                "refs/heads/" + workspace.baseBranch() + "^{commit}")
            .strip();
    return new Status(changes, diff, !currentBase.equals(workspace.baseCommit()), currentBase);
  }

  /** 审查绑定当前提交、差异和目标分支；尚未支持对未跟踪文件的完整性签名。 */
  public String approvalFingerprint(String id) {
    Workspace workspace = get(id);
    Path path = Path.of(workspace.workspacePath());
    Status status = status(id);
    if (status.changes().lines().anyMatch(line -> line.startsWith("?? ")))
      throw new IllegalStateException("存在未跟踪文件，当前版本不能将其完整绑定到审批。");
    if (status.diff().isBlank()) throw new IllegalStateException("没有已跟踪文件差异可供审查。");
    return fingerprint(workspace, status, path);
  }

  /** 测试证据需绑定当前文件和分支；未跟踪文件不能被该版本完整签名。 */
  public String verificationFingerprint(String id) {
    Workspace workspace = get(id);
    Status status = status(id);
    var declared = declaredFiles(id);
    var untracked =
        status.changes().lines()
            .filter(line -> line.startsWith("?? "))
            .map(line -> unquote(line.substring(3).strip()))
            .toList();
    if (untracked.stream().anyMatch(path -> !declared.containsKey(path)))
      throw new IllegalStateException("存在未跟踪文件，不能可靠绑定测试结果。");
    // 已声明的补充文件按路径和实际内容参与指纹；内容变化会让旧证据失效。
    var bound = new java.util.TreeMap<String, String>();
    for (var path : declared.keySet()) {
      Path file = resolveFile(workspace, path);
      if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
        throw new IllegalStateException("补充文件缺失或不是普通文件，不能绑定测试结果：" + path);
      try {
        if (Files.size(file) > 1_000_000)
          throw new IllegalStateException("补充文件超过允许大小，不能绑定测试结果：" + path);
      } catch (IOException e) {
        throw new IllegalStateException("无法读取补充文件，不能绑定测试结果：" + path);
      }
      bound.put(path, hashFile(file));
    }
    return fingerprint(workspace, status, Path.of(workspace.workspacePath()), bound);
  }

  private static String unquote(String path) {
    if (path.length() >= 2 && path.startsWith("\"") && path.endsWith("\""))
      return path.substring(1, path.length() - 1);
    return path;
  }

  private Map<String, String> declaredFiles(String workspaceId) {
    var result = new java.util.LinkedHashMap<String, String>();
    db.query(
        "SELECT path,sha256 FROM workspace_declared_files WHERE workspace_id=?",
        (org.springframework.jdbc.core.RowCallbackHandler)
            row -> result.put(row.getString("path"), row.getString("sha256")),
        workspaceId);
    return result;
  }

  private String fingerprint(Workspace workspace, Status status, Path path) {
    return fingerprint(workspace, status, path, Map.of());
  }

  private String fingerprint(
      Workspace workspace, Status status, Path path, Map<String, String> declared) {
    String head = runGit(path, "rev-parse", "--verify", "HEAD").strip();
    String binaryDiff =
        runGit(path, "diff", "--binary", "--no-ext-diff", "--no-textconv", "HEAD", "--", ".");
    StringBuilder extra = new StringBuilder();
    declared.forEach(
        (file, sha) -> extra.append("\nDECLARED\n").append(file).append('\n').append(sha));
    return hash(
        head
            + "\n"
            + status.currentBase()
            + "\n"
            + status.changes()
            + "\n"
            + binaryDiff
            + extra);
  }

  private String hashFile(Path file) {
    try (var input = Files.newInputStream(file)) {
      var digest = java.security.MessageDigest.getInstance("SHA-256");
      byte[] buffer = new byte[8192];
      int read;
      while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read);
      return java.util.HexFormat.of().formatHex(digest.digest());
    } catch (IOException | java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException("无法计算补充文件摘要。", e);
    }
  }

  /** 本地提交已批准的全部已跟踪差异；调用方必须持有当前服务对象的锁并核验审批。 Git 不属于数据库事务，异常时提交结果需要人工核对。 */
  String commitApproved(String workspaceId, String approvedFingerprint, String message) {
    if (!approvalFingerprint(workspaceId).equals(approvedFingerprint))
      throw new IllegalStateException("工作区或目标分支已变化。");
    Workspace workspace = get(workspaceId);
    Path path = Path.of(workspace.workspacePath());
    String head = runGit(path, "rev-parse", "--verify", "HEAD").strip();
    Status before = status(workspaceId);
    String expectedDiff =
        runGit(path, "diff", "--binary", "--no-ext-diff", "--no-textconv", "HEAD", "--", ".");
    runGit(path, "add", "-u", "--", ".");
    String stagedDiff =
        runGit(
            path,
            "diff",
            "--cached",
            "--binary",
            "--no-ext-diff",
            "--no-textconv",
            "HEAD",
            "--",
            ".");
    String unstaged = runGit(path, "diff", "--binary", "--no-ext-diff", "--no-textconv", "--", ".");
    Status after = status(workspaceId);
    if (!expectedDiff.equals(stagedDiff)
        || !unstaged.isBlank()
        || !before.currentBase().equals(after.currentBase())
        || !head.equals(runGit(path, "rev-parse", "--verify", "HEAD").strip())
        || after.changes().lines().anyMatch(line -> line.startsWith("?? ")))
      throw new IllegalStateException("暂存后工作区状态变化，停止提交。");
    runGit(path, "commit", "--no-verify", "-m", message);
    String commitId = runGit(path, "rev-parse", "--verify", "HEAD").strip();
    if (!commitId.matches("[0-9a-fA-F]{40,64}") || head.equals(commitId))
      throw new IllegalStateException("提交结果无法确认。");
    return commitId;
  }

  /** 只读取工作区内普通文本文件；链接和 .git 元数据均不允许访问。 */
  public FileContent read(String id, String relative) {
    Path target = resolveFile(id, relative);
    try {
      if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) || Files.size(target) > 1_000_000)
        throw new IllegalArgumentException("文件不存在或超过 1MB。");
      String content = Files.readString(target, StandardCharsets.UTF_8);
      return new FileContent(content, hash(content));
    } catch (IOException e) {
      throw new IllegalArgumentException("无法读取文本文件。");
    }
  }

  /** 用户明确编辑现有文件；版本不一致时拒绝覆盖，未提供任意新文件写入。 */
  public synchronized FileContent write(
      String id, String relative, String expectedHash, String content) {
    if (content == null || content.getBytes(StandardCharsets.UTF_8).length > 1_000_000)
      throw new IllegalArgumentException("文件内容为空或超过 1MB。");
    FileContent original = read(id, relative);
    if (expectedHash == null || !expectedHash.equals(original.sha256()))
      throw new WorkspaceVersionConflict("文件已变化，请重新读取并确认后再保存。");
    Path target = resolveFile(id, relative);
    Path temporary = null;
    try {
      // 临时文件与目标同目录，避免写入失败将目标截断；本地版不声称防御恶意进程的路径竞争。
      temporary = Files.createTempFile(target.getParent(), ".agent-edit-", ".tmp");
      Files.writeString(temporary, content, StandardCharsets.UTF_8);
      try {
        var permissions = Files.getPosixFilePermissions(target, LinkOption.NOFOLLOW_LINKS);
        Files.setPosixFilePermissions(temporary, permissions);
      } catch (UnsupportedOperationException ignored) {
        /* Windows 文件系统不一定支持 POSIX 权限。 */
      }
      if (!read(id, relative).sha256().equals(expectedHash))
        throw new WorkspaceVersionConflict("文件已变化，请重新读取并确认后再保存。");
      Files.move(
          temporary,
          target,
          java.nio.file.StandardCopyOption.ATOMIC_MOVE,
          java.nio.file.StandardCopyOption.REPLACE_EXISTING);
      return new FileContent(content, hash(content));
    } catch (IOException e) {
      throw new IllegalStateException("无法安全保存文件，原文件未确认更新。");
    } finally {
      if (temporary != null)
        try {
          Files.deleteIfExists(temporary);
        } catch (IOException ignored) {
          /* 保留现场供用户检查。 */
        }
    }
  }

  /**
   * 任务补充写入的测试文件：只允许新建 {@code tests/test_*.py}，不覆盖任何现有文件。
   *
   * <p>文件保持未跟踪状态，由用户在代码工作区核对；该入口只服务补充信息，不扩大 Agent 工具授权。
   */
  public String createSupplementTestFile(String id, String relative, String content) {
    var workspace = get(id);
    requireTestFilePath(relative);
    Path target = resolveFile(workspace, relative);
    byte[] bytes = content == null ? new byte[0] : content.getBytes(StandardCharsets.UTF_8);
    try {
      Files.createDirectories(target.getParent());
      // 原子新建：并发提交同名文件时只有一个请求能创建成功，另一个明确拒绝，不覆盖已有内容。
      Files.write(target, bytes, java.nio.file.StandardOpenOption.CREATE_NEW,
          java.nio.file.StandardOpenOption.WRITE);
    } catch (java.nio.file.FileAlreadyExistsException e) {
      throw new IllegalArgumentException("目标文件已存在，不能覆盖现有文件。");
    } catch (IOException e) {
      throw new IllegalStateException("无法写入补充测试文件。");
    }
    String sha = hash(content);
    try {
      // 显式声明的补充文件才允许以未跟踪状态进入证据指纹；其他未跟踪文件仍被拒绝。
      db.update(
          "MERGE INTO workspace_declared_files(workspace_id,path,sha256,source,created_at)"
              + " KEY(workspace_id,path) VALUES(?,?,?,?,?)",
          workspace.id(),
          relative,
          sha,
          "SUPPLEMENT",
          java.time.Instant.now().toString());
      return sha;
    } catch (RuntimeException failure) {
      // 声明入库失败时只回滚本次 CREATE_NEW 创建的文件；写入前已存在的文件不会被删除。
      try {
        Files.deleteIfExists(target);
      } catch (IOException ignored) {
        /* 保留现场供人工检查；调用方仍会收到失败原因。 */
      }
      throw failure;
    }
  }

  /** 补充事务回滚时尽力删除刚写入的文件；失败不阻断回滚，保留现场供用户核对。 */
  public void discardSupplementTestFile(String id, String relative) {
    if (!isTestFilePath(relative)) return;
    try {
      Files.deleteIfExists(resolveFile(get(id), relative));
      db.update(
          "DELETE FROM workspace_declared_files WHERE workspace_id=? AND path=?", id, relative);
    } catch (IOException | IllegalArgumentException ignored) {
      /* 保留现场供用户检查。 */
    }
  }

  private static void requireTestFilePath(String relative) {
    if (!isTestFilePath(relative))
      throw new IllegalArgumentException("测试文件路径必须是 tests/ 下的 test_*.py。");
  }

  private static boolean isTestFilePath(String relative) {
    return relative != null && relative.matches("tests/test_[A-Za-z0-9_\\-]+\\.py");
  }

  private Path resolveFile(String id, String relative) {
    return resolveFile(get(id), relative);
  }

  private Path resolveFile(Workspace workspace, String relative) {
    if (relative == null || relative.isBlank()) throw new IllegalArgumentException("请指定相对文件路径。");
    Path requested = Path.of(relative);
    if (requested.isAbsolute()) throw new IllegalArgumentException("必须使用相对路径。");
    Path root = Path.of(workspace.workspacePath()).toAbsolutePath().normalize();
    Path target = root.resolve(requested).normalize();
    if (!target.startsWith(root) || requested.normalize().startsWith(".git"))
      throw new IllegalArgumentException("路径不在授权范围内。");
    Path current = root;
    for (Path part : root.relativize(target)) {
      if (part.toString().equalsIgnoreCase(".git"))
        throw new IllegalArgumentException("不允许访问 Git 元数据。");
      current = current.resolve(part);
      if (Files.isSymbolicLink(current)) throw new IllegalArgumentException("不允许访问符号链接。");
    }
    return target;
  }

  /** 只检查候选路径，不读取内容，也不代替任务创建与工具调用时的重复校验。 */
  boolean isFileCandidate(Workspace workspace, String relative) {
    try {
      if (!relative.strip().equals(relative)
          || !AuthorizedFileSet.normalize(relative).equals(relative)) return false;
      Path target = resolveFile(workspace, relative);
      return Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)
          && Files.size(target) <= 1_000_000;
    } catch (IllegalArgumentException | IOException e) {
      return false;
    }
  }

  private String hash(String text) {
    try {
      return java.util.HexFormat.of()
          .formatHex(
              java.security.MessageDigest.getInstance("SHA-256")
                  .digest(text.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  String runGit(Path dir, String... args) {
    List<String> command =
        new ArrayList<>(
            List.of(
                "git",
                "-c",
                "core.hooksPath=" + workspaceRoot.resolve("disabled-hooks"),
                "-c",
                "core.fsmonitor=false",
                "-C",
                dir.toString()));
    command.addAll(List.of(args));
    try {
      ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
      builder.environment().put("GIT_TERMINAL_PROMPT", "0");
      Process process = builder.start();
      // 限制内存占用并并行读取，避免子进程输出填满管道后死锁。
      var reader =
          java.util.concurrent.CompletableFuture.supplyAsync(
              () -> {
                try (var in = process.getInputStream();
                    var out = new java.io.ByteArrayOutputStream()) {
                  byte[] buffer = new byte[8192];
                  int n, total = 0;
                  while ((n = in.read(buffer)) != -1) {
                    total += n;
                    if (total <= OUTPUT_LIMIT) out.write(buffer, 0, n);
                  }
                  if (total > OUTPUT_LIMIT) throw new IllegalStateException("Git 输出超过允许大小。");
                  return out.toString(StandardCharsets.UTF_8);
                } catch (IOException e) {
                  throw new IllegalStateException("无法读取 Git 输出。");
                }
              });
      if (!process.waitFor(Duration.ofSeconds(30).toMillis(), TimeUnit.MILLISECONDS)) {
        process.destroyForcibly();
        throw new IllegalStateException("Git 操作超时。");
      }
      String output = reader.join();
      if (process.exitValue() != 0) throw new IllegalArgumentException("Git 操作失败，请检查仓库、分支和访问权限。");
      return output;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Git 操作已中断。");
    } catch (IOException e) {
      throw new IllegalStateException("无法启动 Git，请检查本机安装。");
    }
  }

  public record Workspace(
      String id,
      String repositoryPath,
      String workspacePath,
      String baseBranch,
      String baseCommit,
      String branchName,
      String createdAt) {}

  public record Status(String changes, String diff, boolean baseChanged, String currentBase) {}

  public record FileContent(String content, String sha256) {}
}
