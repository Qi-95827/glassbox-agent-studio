package dev.workbench.workspace;

import dev.workbench.domain.WorkspaceVersionConflict;
import dev.workbench.persistence.entity.WorkspaceDeclaredFileEntity;
import dev.workbench.persistence.repository.WorkspaceDeclaredFileRepository;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** 工作区文件授权：路径解析、读写、声明文件与摘要；不直接承载 Git 交付命令。 */
final class WorkspaceFileService {
  private static final int FILE_LIMIT = 1_000_000;
  private static final Pattern IDEA_FILE =
      Pattern.compile(
          "[a-zA-Z0-9][a-zA-Z0-9_.-]{0,70}\\.(html|css|js|md|txt|json|py|ts|jsx|tsx|java|svg)",
          Pattern.CASE_INSENSITIVE);
  private static final Pattern RESERVED_FILE =
      Pattern.compile("(con|prn|aux|nul|com[0-9]|lpt[0-9])\\..*", Pattern.CASE_INSENSITIVE);

  private final WorkspaceDeclaredFileRepository declaredFileRows;
  private final Path workspaceRoot;

  WorkspaceFileService(WorkspaceDeclaredFileRepository declaredFileRows, Path workspaceRoot) {
    this.declaredFileRows = declaredFileRows;
    this.workspaceRoot = workspaceRoot;
  }

  /** 独立交付仅允许受限的单层文本文件名。 */
  static List<String> ideaFiles(List<String> files) {
    if (files == null || files.isEmpty() || files.size() > 5)
      throw new IllegalArgumentException("独立工作区需要 1 至 5 个交付文件。");
    var seen = new HashSet<String>();
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

  /** 只读取工作区内普通文本文件；链接和 .git 元数据均不允许访问。 */
  WorkspaceService.FileContent read(WorkspaceService.Workspace workspace, String relative) {
    Path target = resolveFile(workspace, relative);
    try {
      if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) || Files.size(target) > FILE_LIMIT)
        throw new IllegalArgumentException("文件不存在或超过 1MB。");
      String content = Files.readString(target, StandardCharsets.UTF_8);
      return new WorkspaceService.FileContent(content, hash(content));
    } catch (IOException e) {
      throw new IllegalArgumentException("无法读取文本文件。");
    }
  }

  /** 用户明确编辑现有文件；版本不一致时拒绝覆盖，未提供任意新文件写入。 */
  WorkspaceService.FileContent write(
      WorkspaceService.Workspace workspace, String relative, String expectedHash, String content) {
    if (content == null || content.getBytes(StandardCharsets.UTF_8).length > FILE_LIMIT)
      throw new IllegalArgumentException("文件内容为空或超过 1MB。");
    WorkspaceService.FileContent original = read(workspace, relative);
    if (expectedHash == null || !expectedHash.equals(original.sha256()))
      throw new WorkspaceVersionConflict("文件已变化，请重新读取并确认后再保存。");
    Path target = resolveFile(workspace, relative);
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
      if (!read(workspace, relative).sha256().equals(expectedHash))
        throw new WorkspaceVersionConflict("文件已变化，请重新读取并确认后再保存。");
      Files.move(
          temporary,
          target,
          java.nio.file.StandardCopyOption.ATOMIC_MOVE,
          java.nio.file.StandardCopyOption.REPLACE_EXISTING);
      return new WorkspaceService.FileContent(content, hash(content));
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

  /** 用户显式新建文本文件；原子创建并登记，声明失败只删除本次创建的文件。 */
  WorkspaceService.FileContent createUserFile(
      WorkspaceService.Workspace workspace, String relative, String content) {
    if (content == null || content.getBytes(StandardCharsets.UTF_8).length > FILE_LIMIT)
      throw new IllegalArgumentException("文件内容不能为空或超过 1MB。");
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
      declareFile(workspace.id(), path, sha, "USER");
      return new WorkspaceService.FileContent(content, sha);
    } catch (RuntimeException failure) {
      try {
        Files.deleteIfExists(target);
      } catch (IOException ignored) {
        /* 保留现场供人工检查。 */
      }
      throw failure;
    }
  }

  /**
   * 任务补充写入的测试文件：只允许新建 {@code tests/test_*.py}，不覆盖任何现有文件。
   *
   * <p>文件保持未跟踪状态，由用户在代码工作区核对；该入口只服务补充信息，不扩大 Agent 工具授权。
   */
  String createSupplementTestFile(
      WorkspaceService.Workspace workspace, String relative, String content) {
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
  void discardSupplementTestFile(WorkspaceService.Workspace workspace, String relative) {
    if (!isTestFilePath(relative)) return;
    try {
      Files.deleteIfExists(resolveFile(workspace, relative));
      declaredFileRows.deleteForWorkspace(workspace.id(), relative);
    } catch (IOException | IllegalArgumentException ignored) {
      /* 保留现场供用户检查。 */
    }
  }

  private static void requireTestFilePath(String relative) {
    if (!isTestFilePath(relative))
      throw new IllegalArgumentException("测试文件路径必须是 tests/ 下的 test_*.py。");
  }

  static boolean isTestFilePath(String relative) {
    return relative != null && relative.matches("tests/test_[A-Za-z0-9_\\-]+\\.py");
  }

  Map<String, String> declaredFiles(String workspaceId) {
    var result = new LinkedHashMap<String, String>();
    for (WorkspaceDeclaredFileEntity row : declaredFileRows.findByWorkspaceId(workspaceId))
      result.put(row.getPath(), row.getSha256());
    return result;
  }

  /** 声明文件使用合并语义：同一 (workspace_id, path) 再次声明时更新摘要和来源。 */
  void declareFile(String workspaceId, String path, String sha256, String source) {
    declaredFileRows.save(
        new WorkspaceDeclaredFileEntity(
            workspaceId, path, sha256, source, java.time.Instant.now().toString()));
  }

  String hashUntracked(WorkspaceService.Workspace workspace, String relative) {
    Path target = resolveFile(workspace, relative);
    try {
      if (Files.isSymbolicLink(target) || !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS))
        throw new IllegalStateException("未跟踪文件不是普通文件：" + relative);
      if (Files.size(target) > FILE_LIMIT)
        throw new IllegalStateException("未跟踪文件超过允许大小：" + relative);
    } catch (IOException e) {
      throw new IllegalStateException("无法读取未跟踪文件：" + relative);
    }
    return hashFile(target);
  }

  long sizeOf(WorkspaceService.Workspace workspace, String relative) {
    try {
      return Files.size(resolveFile(workspace, relative));
    } catch (IOException e) {
      throw new IllegalStateException("无法读取文件大小：" + relative);
    }
  }

  String hashFile(Path file) {
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

  String hash(String text) {
    try {
      return java.util.HexFormat.of()
          .formatHex(
              java.security.MessageDigest.getInstance("SHA-256")
                  .digest(text.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  static String pathIdentity(String relative) {
    if (relative == null || relative.isBlank())
      throw new IllegalArgumentException("请指定相对文件路径。");
    Path path = Path.of(relative);
    if (path.isAbsolute()) throw new IllegalArgumentException("必须使用相对路径。");
    return path.normalize().toString().replace('\\', '/');
  }

  Path resolveFile(WorkspaceService.Workspace workspace, String relative) {
    if (relative == null || relative.isBlank())
      throw new IllegalArgumentException("请指定相对文件路径。");
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
  List<String> authorizedFiles(
      WorkspaceService.Workspace workspace, List<String> directories, List<String> exclusions) {
    Path root = Path.of(workspace.workspacePath()).toAbsolutePath().normalize();
    List<String> normalizedDirectories = normalizeDirectories(root, directories);
    List<String> normalizedExclusions = normalizeExclusions(exclusions);
    if (normalizedDirectories.isEmpty())
      throw new IllegalArgumentException("目录授权至少需要一个工作区内目录。");
    String output =
        WorkspaceGitDeliveryService.runGitAt(
            workspaceRoot,
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
  void requireDirectoryStillAuthorized(
      WorkspaceService.Workspace workspace,
      List<String> directories,
      List<String> exclusions,
      List<String> files) {
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
    var result = new ArrayList<String>();
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
    var result = new ArrayList<String>();
    for (String raw : exclusions) result.add(AuthorizedFileSet.normalize(raw));
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

  /** 只检查候选路径，不读取内容，也不代替任务创建与工具调用时的重复校验。 */
  boolean isFileCandidate(WorkspaceService.Workspace workspace, String relative) {
    try {
      if (!relative.strip().equals(relative)
          || !AuthorizedFileSet.normalize(relative).equals(relative)) return false;
      Path target = resolveFile(workspace, relative);
      return Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)
          && Files.size(target) <= FILE_LIMIT;
    } catch (IllegalArgumentException | IOException e) {
      return false;
    }
  }
}
