package dev.workbench.workspace;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.workbench.persistence.entity.TaskEntity;
import dev.workbench.persistence.entity.TaskWorkspaceScopeEntity;
import dev.workbench.persistence.repository.TaskRepository;
import dev.workbench.persistence.repository.TaskWorkspaceScopeRepository;
import dev.workbench.store.RunRecordService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 目录授权、排除规则与有效文件集合的服务端校验。
 *
 * <p>创建任务时展开目录并冻结文件清单；运行前重新核对目录、排除项和文件可读性。读取、搜索和写入仍由现有工具在每次调用时校验路径，不因目录授权放宽。
 */
@Service
public class WorkspaceAuthorizationService {
  private final WorkspaceService workspaces;
  private final TaskWorkspaceScopeRepository scopes;
  private final TaskRepository tasks;
  private final ObjectMapper json;

  public WorkspaceAuthorizationService(
      WorkspaceService workspaces,
      TaskWorkspaceScopeRepository scopes,
      TaskRepository tasks,
      ObjectMapper json) {
    this.workspaces = workspaces;
    this.scopes = scopes;
    this.tasks = tasks;
    this.json = json;
  }

  /** 在任务写入前展开目录；显式文件和目录文件合并并去重。 */
  public Prepared prepare(
      String workspaceId,
      List<String> explicitFiles,
      List<String> directories,
      List<String> exclusions) {
    List<String> explicit =
        explicitFiles == null || explicitFiles.isEmpty()
            ? List.of()
            : new AuthorizedFileSet(explicitFiles).paths();
    List<String> dirs = directories == null ? List.of() : List.copyOf(directories);
    List<String> excluded = exclusions == null ? List.of() : List.copyOf(exclusions);
    if (dirs.isEmpty()) {
      return new Prepared(explicit, dirs, excluded, explicit);
    }
    if (workspaceId == null) throw new IllegalArgumentException("目录授权必须选择工作区。");
    var effective = new LinkedHashSet<>(workspaces.authorizedFiles(workspaceId, dirs, excluded));
    effective.addAll(explicit);
    if (effective.size() > 200)
      throw new IllegalArgumentException("目录授权展开后不能超过 200 个文件。");
    return new Prepared(explicit, dirs, excluded, List.copyOf(effective));
  }

  /** 将已展开范围与任务一起保存；同一事务中失败不会留下半成品策略。 */
  @Transactional
  public void save(String taskId, String workspaceId, Prepared prepared) {
    if (workspaceId == null) return;
    try {
      String directories = json.writeValueAsString(prepared.directories());
      String exclusions = json.writeValueAsString(prepared.exclusions());
      String explicit = json.writeValueAsString(prepared.explicitFiles());
      String effective = json.writeValueAsString(prepared.effectiveFiles());
      String sha =
          sha(String.join("\n", directories, exclusions, explicit, effective));
      scopes.save(
          new TaskWorkspaceScopeEntity(
              taskId,
              workspaceId,
              directories,
              exclusions,
              explicit,
              effective,
              sha,
              Instant.now().toString()));
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("授权目录范围无法保存。", e);
    }
  }

  /** 运行开始与工具边界使用；没有目录策略的历史任务保持现有文件白名单行为。 */
  public void requireCurrent(String taskId) {
    var row = scopes.findById(taskId).orElse(null);
    if (row == null) return;
    var directories = readList(row.getDirectoryScopesJson());
    var exclusions = readList(row.getExcludedPathsJson());
    var effective = readList(row.getEffectiveFilesJson());
    if (directories.isEmpty()) return;
    workspaces.requireDirectoryStillAuthorized(
        row.getWorkspaceId(), directories, exclusions, effective);
  }

  public java.util.Map<String, Object> find(String taskId) {
    var row = scopes.findById(taskId).orElse(null);
    if (row == null) return null;
    var result = new java.util.LinkedHashMap<String, Object>();
    result.put("TASK_ID", row.getTaskId());
    result.put("WORKSPACE_ID", row.getWorkspaceId());
    result.put("DIRECTORY_SCOPES", readList(row.getDirectoryScopesJson()));
    result.put("EXCLUDED_PATHS", readList(row.getExcludedPathsJson()));
    result.put("EXPLICIT_FILES", readList(row.getExplicitFilesJson()));
    result.put("EFFECTIVE_FILES", readList(row.getEffectiveFilesJson()));
    result.put("SCOPE_SHA256", row.getScopeSha256());
    result.put("CREATED_AT", row.getCreatedAt());
    return result;
  }

  /** 撤销一个目录并重算有效文件；撤销后旧路径在下一次服务端校验中失败。 */
  @Transactional
  public Prepared revoke(String taskId, String directory) {
    var row =
        scopes
            .findById(taskId)
            .orElseThrow(() -> new IllegalArgumentException("该任务没有目录授权记录。"));
    String normalized = AuthorizedFileSet.normalize(directory);
    var directories = new ArrayList<>(readList(row.getDirectoryScopesJson()));
    if (!directories.remove(normalized))
      throw new IllegalArgumentException("该路径不在目录授权中。");
    var explicit = readList(row.getExplicitFilesJson());
    var exclusions = readList(row.getExcludedPathsJson());
    var effective = new LinkedHashSet<String>();
    if (!directories.isEmpty())
      effective.addAll(workspaces.authorizedFiles(row.getWorkspaceId(), directories, exclusions));
    effective.addAll(explicit);
    if (effective.isEmpty())
      throw new IllegalStateException("撤销后没有剩余授权文件；请改为新建任务或重新授权。");
    Prepared prepared = new Prepared(explicit, directories, exclusions, List.copyOf(effective));
    save(taskId, row.getWorkspaceId(), prepared);
    TaskEntity task =
        tasks.findById(taskId).orElseThrow(() -> new IllegalArgumentException("任务不存在。"));
    try {
      task.setFileScopesJson(json.writeValueAsString(prepared.effectiveFiles()));
      task.setFileScope(prepared.effectiveFiles().getFirst());
      tasks.save(task);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("撤销后的文件范围无法保存。", e);
    }
    return prepared;
  }

  private List<String> readList(String value) {
    try {
      return json.readValue(
          value,
          json.getTypeFactory().constructCollectionType(List.class, String.class));
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("已保存的授权范围无效。", e);
    }
  }

  private static String sha(String text) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  public record Prepared(
      List<String> explicitFiles,
      List<String> directories,
      List<String> exclusions,
      List<String> effectiveFiles) {
    public Prepared {
      explicitFiles = List.copyOf(explicitFiles);
      directories = List.copyOf(directories);
      exclusions = List.copyOf(exclusions);
      effectiveFiles = List.copyOf(effectiveFiles);
    }
  }
}
