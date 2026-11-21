package dev.workbench.workspace;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

/** Git 交付：状态、差异、审批/快照/验证指纹、提交与远端信息。 */
final class WorkspaceGitDeliveryService {
  private static final int OUTPUT_LIMIT = 1_000_000;
  private static final int FILE_LIMIT = 1_000_000;
  private final WorkspaceFileService files;
  private final Path workspaceRoot;

  WorkspaceGitDeliveryService(WorkspaceFileService files, Path workspaceRoot) {
    this.files = files;
    this.workspaceRoot = workspaceRoot;
  }

  /** 显示真实差异和目标分支是否发生变化，不自动合并或推送。 */
  WorkspaceService.Status status(WorkspaceService.Workspace workspace) {
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
    return new WorkspaceService.Status(
        changes, diff, !currentBase.equals(workspace.baseCommit()), currentBase);
  }

  /** 审查绑定当前提交、差异、目标分支和已复核的未跟踪文件。 */
  String approvalFingerprint(WorkspaceService.Workspace workspace) {
    Path path = Path.of(workspace.workspacePath());
    WorkspaceService.Status status = status(workspace);
    var untracked = untrackedPaths(status);
    var declared = files.declaredFiles(workspace.id());
    for (String file : untracked)
      if (!declared.containsKey(file))
        throw new IllegalStateException("存在未复核的未跟踪文件，当前版本不能将其完整绑定到审批。");
    if (status.diff().isBlank() && untracked.isEmpty())
      throw new IllegalStateException("没有已跟踪文件差异或已复核的新文件可供审查。");
    var bound = new TreeMap<String, String>();
    for (String file : untracked) bound.put(file, files.hashUntracked(workspace, file));
    return fingerprint(workspace, status, path, bound);
  }

  /** 人工接手快照：包含未跟踪文件的实际内容，不要求预先声明。 */
  String snapshotFingerprint(WorkspaceService.Workspace workspace) {
    WorkspaceService.Status status = status(workspace);
    var bound = new TreeMap<String, String>(files.declaredFiles(workspace.id()));
    for (String file : untrackedPaths(status))
      bound.put(file, files.hashUntracked(workspace, file));
    return fingerprint(workspace, status, Path.of(workspace.workspacePath()), bound);
  }

  /** 列出未跟踪文件及是否已声明；只读，不修改工作区。 */
  List<WorkspaceService.UntrackedFile> untrackedFiles(WorkspaceService.Workspace workspace) {
    var declared = files.declaredFiles(workspace.id());
    return untrackedPaths(status(workspace)).stream()
        .map(
            file -> {
              Path target = files.resolveFile(workspace, file);
              long size;
              try {
                size = Files.size(target);
              } catch (IOException e) {
                throw new IllegalStateException("无法读取未跟踪文件大小：" + file);
              }
              return new WorkspaceService.UntrackedFile(
                  file,
                  size,
                  files.hashUntracked(workspace, file),
                  declared.containsKey(file),
                  declared.get(file));
            })
        .toList();
  }

  boolean isUntracked(WorkspaceService.Workspace workspace, String path) {
    return untrackedPaths(status(workspace)).stream().anyMatch(path::equals);
  }

  /** 测试证据需绑定当前文件和分支；未跟踪文件不能被该版本完整签名。 */
  String verificationFingerprint(WorkspaceService.Workspace workspace) {
    WorkspaceService.Status status = status(workspace);
    var declared = files.declaredFiles(workspace.id());
    var untracked = untrackedPaths(status);
    if (untracked.stream().anyMatch(path -> !declared.containsKey(path)))
      throw new IllegalStateException("存在未跟踪文件，不能可靠绑定测试结果。");
    // 已声明的补充文件按路径和实际内容参与指纹；内容变化会让旧证据失效。
    var bound = new TreeMap<String, String>();
    for (var path : declared.keySet()) {
      Path file = files.resolveFile(workspace, path);
      if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
        throw new IllegalStateException("补充文件缺失或不是普通文件，不能绑定测试结果：" + path);
      try {
        if (Files.size(file) > FILE_LIMIT)
          throw new IllegalStateException("补充文件超过允许大小，不能绑定测试结果：" + path);
      } catch (IOException e) {
        throw new IllegalStateException("无法读取补充文件，不能绑定测试结果：" + path);
      }
      bound.put(path, files.hashFile(file));
    }
    return fingerprint(workspace, status, Path.of(workspace.workspacePath()), bound);
  }

  /** 本地提交已批准的已跟踪差异和已复核新文件；调用方必须持有当前服务对象的锁并核验审批。 Git 不属于数据库事务，异常时提交结果需要人工核对。 */
  String commitApproved(
      WorkspaceService.Workspace workspace, String approvedFingerprint, String message) {
    if (!approvalFingerprint(workspace).equals(approvedFingerprint))
      throw new IllegalStateException("工作区或目标分支已变化。");
    Path path = Path.of(workspace.workspacePath());
    String head = runGit(path, "rev-parse", "--verify", "HEAD").strip();
    WorkspaceService.Status before = status(workspace);
    var declared = files.declaredFiles(workspace.id());
    // 已复核的新文件必须先按原摘要加入暂存区；未复核文件仍会被拒绝。
    runGit(path, "add", "-u", "--", ".");
    for (String file : declared.keySet()) {
      Path target = files.resolveFile(workspace, file);
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
    WorkspaceService.Status after = status(workspace);
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
      Path target = files.resolveFile(workspace, file);
      if (Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)
          && !files.hashFile(target).equals(declared.get(file)))
        throw new IllegalStateException("已复核文件在暂存期间变化，停止提交：" + file);
    }
    runGit(path, "commit", "--no-verify", "-m", message);
    String commitId = runGit(path, "rev-parse", "--verify", "HEAD").strip();
    if (!commitId.matches("[0-9a-fA-F]{40,64}") || head.equals(commitId))
      throw new IllegalStateException("提交结果无法确认。");
    return commitId;
  }

  String head(WorkspaceService.Workspace workspace) {
    return runGit(Path.of(workspace.workspacePath()), "rev-parse", "--verify", "HEAD").strip();
  }

  /** 读取本地分支当前提交；用于合并审批绑定被合入版本。 */
  String branchCommit(WorkspaceService.Workspace workspace, String branch) {
    Path repository = Path.of(workspace.repositoryPath());
    requireBranchName(repository, branch);
    String commit =
        runGit(repository, "rev-parse", "--verify", "refs/heads/" + branch + "^{commit}").strip();
    if (!commit.matches("[0-9a-fA-F]{40,64}"))
      throw new IllegalStateException("无法确认待合入分支提交。");
    return commit;
  }

  String currentBranch(WorkspaceService.Workspace workspace) {
    return runGit(Path.of(workspace.workspacePath()), "rev-parse", "--abbrev-ref", "HEAD").strip();
  }

  /** 解析实际推送地址；只以摘要进入审批，避免数据库或日志保存 URL 中的凭据。 */
  String remotePushUrlFingerprint(WorkspaceService.Workspace workspace, String remote) {
    if (remote == null || !remote.matches("[A-Za-z0-9._-]{1,80}") || remote.startsWith("-"))
      throw new IllegalArgumentException("远端名称无效。");
    String url =
        runGit(Path.of(workspace.workspacePath()), "remote", "get-url", "--push", remote).strip();
    if (url.isBlank() || url.contains("\n") || url.contains("\r"))
      throw new IllegalStateException("无法解析远端推送地址。");
    return files.hash(url);
  }

  String remotePushUrl(WorkspaceService.Workspace workspace, String remote) {
    return runGit(Path.of(workspace.workspacePath()), "remote", "get-url", "--push", remote).strip();
  }

  void requireBranchName(Path repository, String branch) {
    if (branch == null || branch.isBlank() || branch.startsWith("-"))
      throw new IllegalArgumentException("请填写有效的分支名。");
    runGit(repository, "check-ref-format", "refs/heads/" + branch);
  }

  /** 只对指定授权路径生成差异，避免把工作区其他修改带入成果。 */
  String diffForPaths(WorkspaceService.Workspace workspace, List<String> paths) {
    if (paths == null || paths.isEmpty()) throw new IllegalArgumentException("请指定要比较的授权文件。");
    var normalized = new ArrayList<String>();
    for (String path : paths) normalized.add(AuthorizedFileSet.normalize(path));
    var command = new ArrayList<String>();
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

  private String fingerprint(
      WorkspaceService.Workspace workspace,
      WorkspaceService.Status status,
      Path path,
      Map<String, String> declared) {
    String head = runGit(path, "rev-parse", "--verify", "HEAD").strip();
    String binaryDiff =
        runGit(path, "diff", "--binary", "--no-ext-diff", "--no-textconv", "HEAD", "--", ".");
    StringBuilder extra = new StringBuilder();
    declared.forEach(
        (file, sha) -> extra.append("\nDECLARED\n").append(file).append('\n').append(sha));
    return files.hash(
        head + "\n" + status.currentBase() + "\n" + status.changes() + "\n" + binaryDiff + extra);
  }

  private static List<String> untrackedPaths(WorkspaceService.Status status) {
    return status
        .changes()
        .lines()
        .filter(line -> line.startsWith("?? "))
        .map(line -> unquote(line.substring(3).strip()))
        .toList();
  }

  private static String unquote(String path) {
    if (path.length() >= 2 && path.startsWith("\"") && path.endsWith("\""))
      return path.substring(1, path.length() - 1);
    return path;
  }

  WorkspaceService.GitResult runGitResult(Path dir, String... args) {
    return runGitResultAt(workspaceRoot, dir, args);
  }

  String runGit(Path dir, String... args) {
    return runGitAt(workspaceRoot, dir, args);
  }

  static WorkspaceService.GitResult runGitResultAt(Path workspaceRoot, Path dir, String... args) {
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
      return new WorkspaceService.GitResult(process.exitValue(), reader.join());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Git 操作已中断。");
    } catch (IOException e) {
      throw new IllegalStateException("无法启动 Git，请检查本机安装。");
    }
  }

  static String runGitAt(Path workspaceRoot, Path dir, String... args) {
    WorkspaceService.GitResult result = runGitResultAt(workspaceRoot, dir, args);
    if (result.exitCode() != 0)
      throw new IllegalArgumentException("Git 操作失败，请检查仓库、分支和访问权限。");
    return result.output();
  }
}
