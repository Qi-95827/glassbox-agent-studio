package dev.workbench.workspace;

import dev.workbench.persistence.entity.WorkspaceDeclaredFileEntity;
import dev.workbench.persistence.entity.WorkspaceEntity;
import dev.workbench.persistence.repository.WorkspaceDeclaredFileRepository;
import dev.workbench.persistence.repository.WorkspaceRepository;
import dev.workbench.store.RunRecordService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.stereotype.Service;
import dev.workbench.domain.ExecutionScope;
import dev.workbench.domain.WorkspaceVersionConflict;

/** 个人本地仓库工作区；Git 隔离不等于不可信代码执行沙箱。 */
@Service
public class WorkspaceService {
  private static final int OUTPUT_LIMIT = 1_000_000;
  private static final Pattern IDEA_FILE =
      Pattern.compile(
          "[a-zA-Z0-9][a-zA-Z0-9_.-]{0,70}\\.(html|css|js|md|txt|json|py|ts|jsx|tsx|java|svg)",
          Pattern.CASE_INSENSITIVE);
  private static final Pattern RESERVED_FILE =
      Pattern.compile("(con|prn|aux|nul|com[0-9]|lpt[0-9])\\..*", Pattern.CASE_INSENSITIVE);
  private final WorkspaceRepository workspaceRows;
  private final WorkspaceDeclaredFileRepository declaredFileRows;
  private final Path workspaceRoot;
  private final dev.workbench.ownership.OwnershipService ownership;
  private final dev.workbench.ownership.RepositoryAuthorizationService repositoryAuthorization;

  @Autowired
  public WorkspaceService(
      WorkspaceRepository workspaceRows,
      WorkspaceDeclaredFileRepository declaredFileRows,
      @Value("${workbench.workspace-root:data/workspaces}") String workspaceRoot,
      dev.workbench.ownership.OwnershipService ownership,
      dev.workbench.ownership.RepositoryAuthorizationService repositoryAuthorization) {
    this.workspaceRows = workspaceRows;
    this.declaredFileRows = declaredFileRows;
    this.workspaceRoot = Path.of(workspaceRoot).toAbsolutePath().normalize();
    this.ownership = ownership;
    this.repositoryAuthorization = repositoryAuthorization;
  }

  /** 独立测试可直接构造；没有归属服务时不做登记，旧单机行为保持不变。 */
  public WorkspaceService(
      WorkspaceRepository workspaceRows,
      WorkspaceDeclaredFileRepository declaredFileRows,
      String workspaceRoot) {
    this(workspaceRows, declaredFileRows, workspaceRoot, null, null);
  }

  private String ownerScope() {
    return ownership == null
        ? dev.workbench.domain.ExecutionScope.LOCAL.ownerScopeKey()
        : ownership.scope().ownerScopeKey();
  }

  private void stampOwner(String workspaceId) {
    if (ownership != null)
      ownership.stamp(dev.workbench.ownership.OwnershipService.WORKSPACE, workspaceId);
  }

  /** 用户显式指定仓库和分支；只允许创建独立工作区，不覆盖当前工作区。 */
  public synchronized Workspace create(String repository, String branch) {
    return create(repository, branch, false);
  }

  /**
   * @param internal 服务端自建的独立交付仓库不使用用户仓库授权；用户导入必须先通过仓库授权。
   */
  private synchronized Workspace create(String repository, String branch, boolean internal) {
    if (repository == null || branch == null || branch.isBlank() || branch.startsWith("-"))
      throw new IllegalArgumentException("请填写本地仓库目录与基础分支。");
    Path repo;
    try {
      repo = Path.of(repository).toRealPath();
    } catch (IOException | RuntimeException e) {
      throw new IllegalArgumentException("仓库目录不存在或不可访问。");
    }
    // 先按管理员登记的规范化仓库根目录核验，再调用任何 Git 命令。
    if (!internal && repositoryAuthorization != null)
      repositoryAuthorization.requireAuthorized(repo);
    String branchRef = "refs/heads/" + branch;
    runGit(repo, "check-ref-format", branchRef);
    String top = runGit(repo, "rev-parse", "--show-toplevel").strip();
    Path root;
    try {
      root = Path.of(top).toRealPath();
    } catch (IOException e) {
      throw new IllegalArgumentException("无法定位仓库根目录。");
    }
    if (!root.equals(repo)) throw new IllegalArgumentException("请填写仓库根目录，而不是子目录或非仓库目录。");
    String commit = runGit(root, "rev-parse", "--verify", branchRef + "^{commit}").strip();
    if (!commit.matches("[0-9a-fA-F]{40,64}")) throw new IllegalStateException("无法确定基础提交。");
    String id = dev.workbench.domain.WorkbenchIds.newId();
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
    workspaceRows.save(
        new WorkspaceEntity(
            id, root.toString(), target.toString(), branch, commit, name, dev.workbench.domain.WorkbenchTime.now()));
    stampOwner(id);
    return get(id);
  }

  public List<Workspace> list() {
    return workspaceRows.findAllByOrderByCreatedAtDesc().stream().map(this::map).toList();
  }

  /** 当前会话可读取的工作区；旧记录由归属服务进一步判断。 */
  public List<Workspace> listOwned() {
    return workspaceRows.findAllByOrderByCreatedAtDesc().stream()
        .filter(
            row ->
                ownership == null
                    || ownership.canRead(
                        ownership
                            .owner(dev.workbench.ownership.OwnershipService.WORKSPACE, row.getId())
                            .orElse(null)))
        .map(this::map)
        .toList();
  }

  /** 独立交付仅允许受限的单层文本文件名。 */
  public static List<String> ideaFiles(List<String> files) {
    if (files == null || files.isEmpty() || files.size() > 5)
      throw new IllegalArgumentException("独立工作区需要 1 至 5 个交付文件。");
    var seen = new java.util.HashSet<String>();
    var normalized = new ArrayList<String>();
    for (String file : files) {
      if (file == null) throw new IllegalArgumentException("请填写交付文件名。");
      String name = file.strip();
      if (!IDEA_FILE.matcher(name).matches()
          || RESERVED_FILE.matcher(name).matches()
          || !seen.add(name.toLowerCase(Locale.ROOT)))
        throw new IllegalArgumentException("请使用不同的简单文本文件名，例如 index.html 或 app.js。");
      normalized.add(name);
    }
    return List.copyOf(normalized);
  }

  /** 创建自己的空白基线，之后沿用与手动工作区相同的 Git 隔离与授权规则。 */
  public Workspace createIdeaWorkspace(List<String> files) {
    var names = ideaFiles(files);
    try {
      Path drafts = Files.createDirectories(workspaceRoot.resolve("idea-repositories"));
      return prepareIdeaWorkspace(Files.createTempDirectory(drafts, "idea-"), names, ownerScope());
    } catch (IOException e) {
      throw new IllegalStateException("无法创建独立交付工作区。");
    }
  }

  /**
   * P14：用固定操作标识创建独立工作区。
   *
   * <p>仓库目录由操作标识决定，进程中断后可以按同一标识核对是否已经创建，避免重复准备。
   */
  public Workspace createIdeaWorkspace(List<String> files, String operationId) {
    return createIdeaWorkspace(files, operationId, ownerScope());
  }

  /** 固定操作标识并可显式指定归属；候选确认使用候选保存的归属，而不是临时线程状态。 */
  public Workspace createIdeaWorkspace(
      List<String> files, String operationId, String ownerScopeKey) {
    var names = ideaFiles(files);
    if (operationId == null || !operationId.matches("[0-9a-fA-F-]{36}"))
      throw new IllegalArgumentException("工作区准备标识无效。");
    try {
      Path drafts = Files.createDirectories(workspaceRoot.resolve("idea-repositories"));
      Path repo = drafts.resolve("idea-" + operationId).normalize();
      // 原子创建唯一目录：只有成功创建目录的请求可以继续执行 Git 初始化。
      Files.createDirectory(repo);
      return prepareIdeaWorkspace(repo, names, ownerScopeKey);
    } catch (java.nio.file.FileAlreadyExistsException e) {
      throw new IllegalStateException("该候选的独立工作区目录已经存在，请核对后再重试。");
    } catch (IOException e) {
      throw new IllegalStateException("无法创建独立交付工作区。");
    }
  }

  /** 按操作标识查找已登记的工作区；Git 已完成但候选记录未更新时用于收口。 */
  public java.util.Optional<Workspace> findIdeaWorkspace(String operationId) {
    Path repo = ideaRepository(operationId);
    if (repo == null || !Files.isDirectory(repo)) return java.util.Optional.empty();
    try {
      return workspaceRows.findFirstByRepositoryPath(repo.toRealPath().toString()).map(this::map);
    } catch (IOException e) {
      return java.util.Optional.empty();
    }
  }

  /** 只判断仓库目录是否存在；没有登记行时不能直接复用，需要人工核对。 */
  public boolean ideaRepositoryExists(String operationId) {
    Path repo = ideaRepository(operationId);
    return repo != null && Files.isDirectory(repo);
  }

  private Path ideaRepository(String operationId) {
    if (operationId == null || !operationId.matches("[0-9a-fA-F-]{36}")) return null;
    return workspaceRoot.resolve("idea-repositories").resolve("idea-" + operationId).normalize();
  }

  private Workspace prepareIdeaWorkspace(Path repo, List<String> names, String ownerScopeKey)
      throws IOException {
    // 目录由调用方以原子方式创建；这里只做 Git 初始化和提交。
    runGit(repo, "init", "--initial-branch=main", "--template=");
    for (String name : names) Files.createFile(repo.resolve(name));
    var add = new ArrayList<>(List.of("add", "--force", "--"));
    add.addAll(names);
    runGit(repo, add.toArray(String[]::new));
    runGit(
        repo,
        "-c",
        "user.name=Agent Studio",
        "-c",
        "user.email=agent-studio@localhost",
        "-c",
        "commit.gpgsign=false",
        "commit",
        "-m",
        "Prepare empty task deliverables");
    Workspace workspace = create(repo.toString(), "main", true);
    return workspace;
  }

  public Workspace get(String id) {
    return workspaceRows
        .findById(id)
        .map(this::map)
        .orElseThrow(() -> new EmptyResultDataAccessException(1));
  }

  private Workspace map(WorkspaceEntity row) {
    return new Workspace(
        row.getId(),
        row.getRepositoryPath(),
        row.getWorkspacePath(),
        row.getBaseBranch(),
        row.getBaseCommit(),
        row.getBranchName(),
        row.getCreatedAt());
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

  /** 审查绑定当前提交、差异、目标分支和已复核的未跟踪文件。 */
  public String approvalFingerprint(String id) {
    Workspace workspace = get(id);
    Path path = Path.of(workspace.workspacePath());
    Status status = status(id);
    var untracked = untrackedPaths(status);
    var declared = declaredFiles(id);
    for (String file : untracked)
      if (!declared.containsKey(file))
        throw new IllegalStateException("存在未复核的未跟踪文件，当前版本不能将其完整绑定到审批。");
    if (status.diff().isBlank() && untracked.isEmpty())
      throw new IllegalStateException("没有已跟踪文件差异或已复核的新文件可供审查。");
    var bound = new java.util.TreeMap<String, String>();
    for (String file : untracked) bound.put(file, hashUntracked(workspace, file));
    return fingerprint(workspace, status, path, bound);
  }

  /** 人工接手快照：包含未跟踪文件的实际内容，不要求预先声明。 */
  public String snapshotFingerprint(String id) {
    Workspace workspace = get(id);
    Status status = status(id);
    var bound = new java.util.TreeMap<String, String>(declaredFiles(id));
    for (String file : untrackedPaths(status)) bound.put(file, hashUntracked(workspace, file));
    return fingerprint(workspace, status, Path.of(workspace.workspacePath()), bound);
  }

  /** 列出未跟踪文件及是否已声明；只读，不修改工作区。 */
  public List<UntrackedFile> untrackedFiles(String id) {
    Workspace workspace = get(id);
    var declared = declaredFiles(id);
    return untrackedPaths(status(id)).stream()
        .map(
            file -> {
              Path target = resolveFile(workspace, file);
              long size;
              try {
                size = Files.size(target);
              } catch (IOException e) {
                throw new IllegalStateException("无法读取未跟踪文件大小：" + file);
              }
              return new UntrackedFile(
                  file,
                  size,
                  hashUntracked(workspace, file),
                  declared.containsKey(file),
                  declared.get(file));
            })
        .toList();
  }

  /** 用户明确复核一个未跟踪文件；摘要不符时拒绝声明。 */
  public UntrackedFile declareUntracked(String id, String relative, String expectedSha256) {
    Workspace workspace = get(id);
    String path = pathIdentity(relative);
    boolean found = untrackedPaths(status(id)).stream().anyMatch(path::equals);
    if (!found) throw new IllegalArgumentException("该路径不是当前未跟踪文件。");
    String actual = hashUntracked(workspace, path);
    if (expectedSha256 == null || !actual.equals(expectedSha256))
      throw new IllegalStateException("未跟踪文件内容已变化，请重新读取后再复核。");
    declareFile(id, path, actual, "USER_REVIEWED");
    return new UntrackedFile(path, sizeOf(workspace, path), actual, true, "USER_REVIEWED");
  }

  /** 用户显式新建文本文件；原子创建并登记，声明失败只删除本次创建的文件。 */
  public synchronized FileContent createUserFile(String id, String relative, String content) {
    if (content == null || content.getBytes(StandardCharsets.UTF_8).length > 1_000_000)
      throw new IllegalArgumentException("文件内容不能为空或超过 1MB。");
    Workspace workspace = get(id);
    String path = pathIdentity(relative);
    Path target = resolveFile(workspace, relative);
    byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
    try {
      Files.createDirectories(target.getParent());
      Files.write(
          target,
          bytes,
          java.nio.file.StandardOpenOption.CREATE_NEW,
          java.nio.file.StandardOpenOption.WRITE);
    } catch (java.nio.file.FileAlreadyExistsException e) {
      throw new IllegalArgumentException("目标文件已存在，不能覆盖现有文件。");
    } catch (IOException e) {
      throw new IllegalStateException("无法创建新文件。");
    }
    String sha = hash(content);
    try {
      declareFile(id, path, sha, "USER");
      return new FileContent(content, sha);
    } catch (RuntimeException failure) {
      try {
        Files.deleteIfExists(target);
      } catch (IOException ignored) {
        /* 保留现场供人工检查。 */
      }
      throw failure;
    }
  }

  private static String pathIdentity(String relative) {
    if (relative == null || relative.isBlank()) throw new IllegalArgumentException("请指定相对文件路径。");
    Path path = Path.of(relative);
    if (path.isAbsolute()) throw new IllegalArgumentException("必须使用相对路径。");
    return path.normalize().toString().replace('\\', '/');
  }

  private static List<String> untrackedPaths(Status status) {
    return status
        .changes()
        .lines()
        .filter(line -> line.startsWith("?? "))
        .map(line -> unquote(line.substring(3).strip()))
        .toList();
  }

  private String hashUntracked(Workspace workspace, String relative) {
    Path target = resolveFile(workspace, relative);
    try {
      if (Files.isSymbolicLink(target) || !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS))
        throw new IllegalStateException("未跟踪文件不是普通文件：" + relative);
      if (Files.size(target) > 1_000_000)
        throw new IllegalStateException("未跟踪文件超过允许大小：" + relative);
    } catch (IOException e) {
      throw new IllegalStateException("无法读取未跟踪文件：" + relative);
    }
    return hashFile(target);
  }

  private long sizeOf(Workspace workspace, String relative) {
    try {
      return Files.size(resolveFile(workspace, relative));
    } catch (IOException e) {
      throw new IllegalStateException("无法读取文件大小：" + relative);
    }
  }

  /** 测试证据需绑定当前文件和分支；未跟踪文件不能被该版本完整签名。 */
  public String verificationFingerprint(String id) {
    Workspace workspace = get(id);
    Status status = status(id);
    var declared = declaredFiles(id);
    var untracked =
        status
            .changes()
            .lines()
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
    for (WorkspaceDeclaredFileEntity row : declaredFileRows.findByWorkspaceId(workspaceId))
      result.put(row.getPath(), row.getSha256());
    return result;
  }

  /** 声明文件使用合并语义：同一 (workspace_id, path) 再次声明时更新摘要和来源。 */
  private void declareFile(String workspaceId, String path, String sha256, String source) {
    declaredFileRows.save(
        new WorkspaceDeclaredFileEntity(
            workspaceId, path, sha256, source, java.time.Instant.now().toString()));
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
        head + "\n" + status.currentBase() + "\n" + status.changes() + "\n" + binaryDiff + extra);
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

  /** 本地提交已批准的已跟踪差异和已复核新文件；调用方必须持有当前服务对象的锁并核验审批。 Git 不属于数据库事务，异常时提交结果需要人工核对。 */
  String commitApproved(String workspaceId, String approvedFingerprint, String message) {
    if (!approvalFingerprint(workspaceId).equals(approvedFingerprint))
      throw new IllegalStateException("工作区或目标分支已变化。");
    Workspace workspace = get(workspaceId);
    Path path = Path.of(workspace.workspacePath());
    String head = runGit(path, "rev-parse", "--verify", "HEAD").strip();
    Status before = status(workspaceId);
    var declared = declaredFiles(workspaceId);
    // 已复核的新文件必须先按原摘要加入暂存区；未复核文件仍会被拒绝。
    runGit(path, "add", "-u", "--", ".");
    for (String file : declared.keySet()) {
      Path target = resolveFile(workspace, file);
      if (Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) runGit(path, "add", "--", file);
    }
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
    boolean unclean =
        after
            .changes()
            .lines()
            .anyMatch(
                line ->
                    line.startsWith("?? ")
                        || line.startsWith(" M")
                        || line.startsWith("MM")
                        || line.startsWith(" D")
                        || line.startsWith("AD"));
    if (stagedDiff.isBlank()
        || !unstaged.isBlank()
        || unclean
        || !before.currentBase().equals(after.currentBase())
        || !head.equals(runGit(path, "rev-parse", "--verify", "HEAD").strip()))
      throw new IllegalStateException("暂存后工作区状态变化，停止提交。");
    for (String file : declared.keySet()) {
      Path target = resolveFile(workspace, file);
      if (Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)
          && !hashFile(target).equals(declared.get(file)))
        throw new IllegalStateException("已复核文件在暂存期间变化，停止提交：" + file);
    }
    runGit(path, "commit", "--no-verify", "-m", message);
    String commitId = runGit(path, "rev-parse", "--verify", "HEAD").strip();
    if (!commitId.matches("[0-9a-fA-F]{40,64}") || head.equals(commitId))
      throw new IllegalStateException("提交结果无法确认。");
    return commitId;
  }

  String head(String workspaceId) {
    return runGit(Path.of(get(workspaceId).workspacePath()), "rev-parse", "--verify", "HEAD")
        .strip();
  }

  /** 读取本地分支当前提交；用于合并审批绑定被合入版本。 */
  String branchCommit(String workspaceId, String branch) {
    WorkspaceService.Workspace workspace = get(workspaceId);
    Path repository = Path.of(workspace.repositoryPath());
    requireBranchName(repository, branch);
    String commit =
        runGit(repository, "rev-parse", "--verify", "refs/heads/" + branch + "^{commit}").strip();
    if (!commit.matches("[0-9a-fA-F]{40,64}")) throw new IllegalStateException("无法确认待合入分支提交。");
    return commit;
  }

  String currentBranch(String workspaceId) {
    return runGit(Path.of(get(workspaceId).workspacePath()), "rev-parse", "--abbrev-ref", "HEAD")
        .strip();
  }

  /** 解析实际推送地址；只以摘要进入审批，避免数据库或日志保存 URL 中的凭据。 */
  String remotePushUrlFingerprint(String workspaceId, String remote) {
    if (remote == null || !remote.matches("[A-Za-z0-9._-]{1,80}") || remote.startsWith("-"))
      throw new IllegalArgumentException("远端名称无效。");
    String url =
        runGit(Path.of(get(workspaceId).workspacePath()), "remote", "get-url", "--push", remote)
            .strip();
    if (url.isBlank() || url.contains("\n") || url.contains("\r"))
      throw new IllegalStateException("无法解析远端推送地址。");
    return hash(url);
  }

  String remotePushUrl(String workspaceId, String remote) {
    return runGit(Path.of(get(workspaceId).workspacePath()), "remote", "get-url", "--push", remote)
        .strip();
  }

  void requireBranchName(Path repository, String branch) {
    if (branch == null || branch.isBlank() || branch.startsWith("-"))
      throw new IllegalArgumentException("请填写有效的分支名。");
    runGit(repository, "check-ref-format", "refs/heads/" + branch);
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
      Files.write(
          target,
          bytes,
          java.nio.file.StandardOpenOption.CREATE_NEW,
          java.nio.file.StandardOpenOption.WRITE);
    } catch (java.nio.file.FileAlreadyExistsException e) {
      throw new IllegalArgumentException("目标文件已存在，不能覆盖现有文件。");
    } catch (IOException e) {
      throw new IllegalStateException("无法写入补充测试文件。");
    }
    String sha = hash(content);
    try {
      // 显式声明的补充文件才允许以未跟踪状态进入证据指纹；其他未跟踪文件仍被拒绝。
      declareFile(workspace.id(), relative, sha, "SUPPLEMENT");
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
      declaredFileRows.deleteForWorkspace(id, relative);
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

  /**
   * 目录授权展开：只列出 Git 跟踪或未忽略的普通文本文件，应用排除规则并限制数量。
   *
   * <p>目录本身必须是工作区内真实目录；符号链接、{@code .git} 和排除项都不会进入结果。该结果是服务端冻结的授权快照，模型不能自行扩展。
   */
  public List<String> authorizedFiles(
      String workspaceId, List<String> directories, List<String> exclusions) {
    Workspace workspace = get(workspaceId);
    Path root = Path.of(workspace.workspacePath()).toAbsolutePath().normalize();
    List<String> normalizedDirectories = normalizeDirectories(root, directories);
    List<String> normalizedExclusions = normalizeExclusions(exclusions);
    if (normalizedDirectories.isEmpty()) throw new IllegalArgumentException("目录授权至少需要一个工作区内目录。");
    String output =
        runGit(
            Path.of(workspace.workspacePath()),
            "ls-files",
            "-z",
            "--cached",
            "--others",
            "--exclude-standard",
            "--");
    var result = new java.util.LinkedHashSet<String>();
    for (String path : output.split(String.valueOf((char) 0))) {
      if (path.isEmpty() || !insideDirectories(path, normalizedDirectories)) continue;
      if (excluded(path, normalizedExclusions)) continue;
      if (!isFileCandidate(workspace, path)) continue;
      result.add(path);
      if (result.size() >= 200) break;
    }
    if (result.isEmpty()) throw new IllegalArgumentException("授权目录内没有可用文本文件。");
    return List.copyOf(result);
  }

  /** 目录授权在运行前重新核对：目录仍存在、有效文件仍可读且没有落入排除项。 */
  public void requireDirectoryStillAuthorized(
      String workspaceId, List<String> directories, List<String> exclusions, List<String> files) {
    Workspace workspace = get(workspaceId);
    Path root = Path.of(workspace.workspacePath()).toAbsolutePath().normalize();
    List<String> normalizedDirectories = normalizeDirectories(root, directories);
    List<String> normalizedExclusions = normalizeExclusions(exclusions);
    for (String path : files) {
      if (!insideDirectories(path, normalizedDirectories) || excluded(path, normalizedExclusions))
        throw new IllegalStateException("授权范围已撤销或排除规则已变化：" + path);
      if (!isFileCandidate(workspace, path))
        throw new IllegalStateException("授权文件已不可读或发生变化：" + path);
    }
  }

  private static List<String> normalizeDirectories(Path root, List<String> directories) {
    if (directories == null || directories.isEmpty()) return List.of();
    var result = new java.util.ArrayList<String>();
    for (String raw : directories) {
      String normalized = AuthorizedFileSet.normalize(raw);
      Path target = root.resolve(normalized).normalize();
      if (!target.startsWith(root) || !Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS))
        throw new IllegalArgumentException("授权目录不存在或不是普通目录：" + raw);
      Path current = root;
      for (Path part : root.relativize(target)) {
        current = current.resolve(part);
        if (Files.isSymbolicLink(current))
          throw new IllegalArgumentException("授权目录不能包含符号链接：" + raw);
      }
      result.add(normalized);
    }
    return List.copyOf(result);
  }

  private static List<String> normalizeExclusions(List<String> exclusions) {
    if (exclusions == null || exclusions.isEmpty()) return List.of();
    var result = new java.util.ArrayList<String>();
    for (String raw : exclusions) {
      String normalized = AuthorizedFileSet.normalize(raw);
      result.add(normalized);
    }
    return List.copyOf(result);
  }

  private static boolean insideDirectories(String path, List<String> directories) {
    for (String directory : directories)
      if (path.equals(directory) || path.startsWith(directory + "/")) return true;
    return false;
  }

  private static boolean excluded(String path, List<String> exclusions) {
    for (String exclusion : exclusions)
      if (path.equals(exclusion) || path.startsWith(exclusion + "/")) return true;
    return false;
  }

  /** 只对指定授权路径生成差异，避免把工作区其他修改带入成果。 */
  public String diffForPaths(String id, List<String> paths) {
    if (paths == null || paths.isEmpty()) throw new IllegalArgumentException("请指定要比较的授权文件。");
    Workspace workspace = get(id);
    var normalized = new java.util.ArrayList<String>();
    for (String path : paths) normalized.add(AuthorizedFileSet.normalize(path));
    var command = new java.util.ArrayList<String>();
    // Git 默认把 []*? 当成 pathspec 通配；授权路径必须按字面匹配，避免命中旁边的未授权文件。
    command.add("--literal-pathspecs");
    command.add("diff");
    command.add("--no-ext-diff");
    command.add("--no-textconv");
    command.add("HEAD");
    command.add("--");
    command.addAll(normalized);
    return runGit(Path.of(workspace.workspacePath()), command.toArray(String[]::new));
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

  public record GitResult(int exitCode, String output) {}

  GitResult runGitResult(Path dir, String... args) {
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
      return new GitResult(process.exitValue(), reader.join());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Git 操作已中断。");
    } catch (IOException e) {
      throw new IllegalStateException("无法启动 Git，请检查本机安装。");
    }
  }

  String runGit(Path dir, String... args) {
    GitResult result = runGitResult(dir, args);
    if (result.exitCode() != 0) throw new IllegalArgumentException("Git 操作失败，请检查仓库、分支和访问权限。");
    return result.output();
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

  public record UntrackedFile(
      String path, long size, String sha256, boolean declared, String declarationSource) {}

  public record FileContent(String content, String sha256) {}
}
