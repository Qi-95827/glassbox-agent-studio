package dev.workbench.workspace;

import dev.workbench.persistence.repository.WorkspaceDeclaredFileRepository;
import dev.workbench.persistence.repository.WorkspaceRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.stereotype.Service;

/**
 * 个人本地仓库工作区；Git 隔离不等于不可信代码执行沙箱。
 *
 * <p>兼容门面：公开签名保持不变，文件访问委托 {@link WorkspaceFileService}，Git 交付委托 {@link
 * WorkspaceGitDeliveryService}。
 */
@Service
public class WorkspaceService {
  private final WorkspaceRepository workspaceRows;
  private final WorkspaceFileService files;
  private final WorkspaceGitDeliveryService git;
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
    this.workspaceRoot = Path.of(workspaceRoot).toAbsolutePath().normalize();
    this.files = new WorkspaceFileService(declaredFileRows, this.workspaceRoot);
    this.git = new WorkspaceGitDeliveryService(this.files, this.workspaceRoot);
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
    return create(repository, branch, null, false);
  }

  /** 支持用户可选自定义隔离开发分支名；留空时自动生成 agent/work-xxxx。 */
  public synchronized Workspace create(String repository, String branch, String workBranch) {
    return create(repository, branch, workBranch, false);
  }

  private synchronized Workspace create(String repository, String branch, boolean internal) {
    return create(repository, branch, null, internal);
  }

  /**
   * @param internal 服务端自建的独立交付仓库不使用用户仓库授权；用户导入必须先通过仓库授权。
   */
  private synchronized Workspace create(
      String repository, String branch, String workBranch, boolean internal) {
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
    git.runGit(repo, "check-ref-format", branchRef);
    String top = git.runGit(repo, "rev-parse", "--show-toplevel").strip();
    Path root;
    try {
      root = Path.of(top).toRealPath();
    } catch (IOException e) {
      throw new IllegalArgumentException("无法定位仓库根目录。");
    }
    if (!root.equals(repo))
      throw new IllegalArgumentException("请填写仓库根目录，而不是子目录或非仓库目录。");
    String commit = git.runGit(root, "rev-parse", "--verify", branchRef + "^{commit}").strip();
    if (!commit.matches("[0-9a-fA-F]{40,64}")) throw new IllegalStateException("无法确定基础提交。");
    String id = dev.workbench.domain.WorkbenchIds.newId();
    String name = "agent/work-" + id.substring(0, 12);
    if (workBranch != null && !workBranch.isBlank()) {
      String custom = workBranch.strip();
      if (custom.startsWith("-") || !custom.matches("[A-Za-z0-9._/-]{1,80}")) {
        throw new IllegalArgumentException("自定义分支名无效：仅支持 1-80 位字母、数字、.、_、- 或 /。");
      }
      if (custom.equals(branch)) {
        throw new IllegalArgumentException("开发分支名不能与基础分支相同，必须使用独立分支。");
      }
      git.runGit(root, "check-ref-format", "refs/heads/" + custom);
      name = custom;
    }
    Path target = workspaceRoot.resolve(id).normalize();
    if (!target.startsWith(workspaceRoot)) throw new IllegalStateException("工作区路径越界。");
    try {
      Files.createDirectories(workspaceRoot);
    } catch (IOException e) {
      throw new IllegalStateException("无法创建工作区根目录。");
    }
    // 使用独立 worktree 和分支；命令参数分离，且关闭 Git hooks。
    git.runGit(root, "worktree", "add", "-b", name, "--", target.toString(), commit);
    workspaceRows.save(
        new dev.workbench.persistence.entity.WorkspaceEntity(
            id,
            root.toString(),
            target.toString(),
            branch,
            commit,
            name,
            dev.workbench.domain.WorkbenchTime.now()));
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
    return WorkspaceFileService.ideaFiles(files);
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
    git.runGit(repo, "init", "--initial-branch=main", "--template=");
    for (String name : names) Files.createFile(repo.resolve(name));
    var add = new ArrayList<>(List.of("add", "--force", "--"));
    add.addAll(names);
    git.runGit(repo, add.toArray(String[]::new));
    git.runGit(
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
    return create(repo.toString(), "main", true);
  }

  /**
   * 管理员一键导入前的路径校验：必须是可访问的 Git 仓库根目录。
   *
   * <p>只做只读校验，不创建授权或工作区记录；通过后再登记授权并创建独立 worktree。
   */
  public void requireImportableRepository(String repository) {
    if (repository == null || repository.isBlank())
      throw new IllegalArgumentException("请填写本地仓库目录与基础分支。");
    Path repo;
    try {
      repo = Path.of(repository).toRealPath();
    } catch (IOException | RuntimeException e) {
      throw new IllegalArgumentException("仓库目录不存在或不可访问。");
    }
    if (!Files.isDirectory(repo)) throw new IllegalArgumentException("仓库路径不是目录。");
    String top = git.runGit(repo, "rev-parse", "--show-toplevel").strip();
    Path root;
    try {
      root = Path.of(top).toRealPath();
    } catch (IOException e) {
      throw new IllegalArgumentException("无法定位仓库根目录。");
    }
    if (!root.equals(repo))
      throw new IllegalArgumentException("请填写仓库根目录，而不是子目录或非仓库目录。");
  }

  public Workspace get(String id) {
    return workspaceRows
        .findById(id)
        .map(this::map)
        .orElseThrow(() -> new EmptyResultDataAccessException(1));
  }

  private Workspace map(dev.workbench.persistence.entity.WorkspaceEntity row) {
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
    return git.status(get(id));
  }

  /** 审查绑定当前提交、差异、目标分支和已复核的未跟踪文件。 */
  public String approvalFingerprint(String id) {
    return git.approvalFingerprint(get(id));
  }

  /** 人工接手快照：包含未跟踪文件的实际内容，不要求预先声明。 */
  public String snapshotFingerprint(String id) {
    return git.snapshotFingerprint(get(id));
  }

  /** 列出未跟踪文件及是否已声明；只读，不修改工作区。 */
  public List<UntrackedFile> untrackedFiles(String id) {
    return git.untrackedFiles(get(id));
  }

  /** 用户明确复核一个未跟踪文件；摘要不符时拒绝声明。 */
  public UntrackedFile declareUntracked(String id, String relative, String expectedSha256) {
    Workspace workspace = get(id);
    String path = WorkspaceFileService.pathIdentity(relative);
    if (!git.isUntracked(workspace, path))
      throw new IllegalArgumentException("该路径不是当前未跟踪文件。");
    String actual = files.hashUntracked(workspace, path);
    if (expectedSha256 == null || !actual.equals(expectedSha256))
      throw new IllegalStateException("未跟踪文件内容已变化，请重新读取后再复核。");
    files.declareFile(id, path, actual, "USER_REVIEWED");
    return new UntrackedFile(path, files.sizeOf(workspace, path), actual, true, "USER_REVIEWED");
  }

  /** 用户显式新建文本文件；原子创建并登记，声明失败只删除本次创建的文件。 */
  public synchronized FileContent createUserFile(String id, String relative, String content) {
    return files.createUserFile(get(id), relative, content);
  }

  /** 测试证据需绑定当前文件和分支；未跟踪文件不能被该版本完整签名。 */
  public String verificationFingerprint(String id) {
    return git.verificationFingerprint(get(id));
  }

  /** 只读取工作区内普通文本文件；链接和 .git 元数据均不允许访问。 */
  public FileContent read(String id, String relative) {
    return files.read(get(id), relative);
  }

  /** 用户明确编辑现有文件；版本不一致时拒绝覆盖，未提供任意新文件写入。 */
  public synchronized FileContent write(
      String id, String relative, String expectedHash, String content) {
    return files.write(get(id), relative, expectedHash, content);
  }

  /**
   * 任务补充写入的测试文件：只允许新建 {@code tests/test_*.py}，不覆盖任何现有文件。
   *
   * <p>文件保持未跟踪状态，由用户在代码工作区核对；该入口只服务补充信息，不扩大 Agent 工具授权。
   */
  public String createSupplementTestFile(String id, String relative, String content) {
    return files.createSupplementTestFile(get(id), relative, content);
  }

  /** 补充事务回滚时尽力删除刚写入的文件；失败不阻断回滚，保留现场供用户核对。 */
  public void discardSupplementTestFile(String id, String relative) {
    if (!WorkspaceFileService.isTestFilePath(relative)) return;
    files.discardSupplementTestFile(get(id), relative);
  }

  /**
   * 目录授权展开：只列出 Git 跟踪或未忽略的普通文本文件，应用排除规则并限制数量。
   *
   * <p>目录本身必须是工作区内真实目录；符号链接、{@code .git} 和排除项都不会进入结果。该结果是服务端冻结的授权快照，模型不能自行扩展。
   */
  public List<String> authorizedFiles(
      String workspaceId, List<String> directories, List<String> exclusions) {
    return files.authorizedFiles(get(workspaceId), directories, exclusions);
  }

  /** 目录授权在运行前重新核对：目录仍存在、有效文件仍可读且没有落入排除项。 */
  public void requireDirectoryStillAuthorized(
      String workspaceId, List<String> directories, List<String> exclusions, List<String> files) {
    this.files.requireDirectoryStillAuthorized(
        get(workspaceId), directories, exclusions, files);
  }

  /** 只对指定授权路径生成差异，避免把工作区其他修改带入成果。 */
  public String diffForPaths(String id, List<String> paths) {
    return git.diffForPaths(get(id), paths);
  }

  /** 本地提交已批准的已跟踪差异和已复核新文件；调用方必须持有当前服务对象的锁并核验审批。 Git 不属于数据库事务，异常时提交结果需要人工核对。 */
  String commitApproved(String workspaceId, String approvedFingerprint, String message) {
    return git.commitApproved(get(workspaceId), approvedFingerprint, message);
  }

  String head(String workspaceId) {
    return git.head(get(workspaceId));
  }

  /** 读取本地分支当前提交；用于合并审批绑定被合入版本。 */
  String branchCommit(String workspaceId, String branch) {
    return git.branchCommit(get(workspaceId), branch);
  }

  String currentBranch(String workspaceId) {
    return git.currentBranch(get(workspaceId));
  }

  /** 解析实际推送地址；只以摘要进入审批，避免数据库或日志保存 URL 中的凭据。 */
  String remotePushUrlFingerprint(String workspaceId, String remote) {
    return git.remotePushUrlFingerprint(get(workspaceId), remote);
  }

  String remotePushUrl(String workspaceId, String remote) {
    return git.remotePushUrl(get(workspaceId), remote);
  }

  void requireBranchName(Path repository, String branch) {
    git.requireBranchName(repository, branch);
  }

  /** 只检查候选路径，不读取内容，也不代替任务创建与工具调用时的重复校验。 */
  boolean isFileCandidate(Workspace workspace, String relative) {
    return files.isFileCandidate(workspace, relative);
  }

  public record GitResult(int exitCode, String output) {}

  GitResult runGitResult(Path dir, String... args) {
    return git.runGitResult(dir, args);
  }

  String runGit(Path dir, String... args) {
    return git.runGit(dir, args);
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
