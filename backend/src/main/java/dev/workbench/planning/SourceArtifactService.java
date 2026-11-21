package dev.workbench.planning;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.workbench.domain.RunSnapshot;
import dev.workbench.domain.RunStatus;
import dev.workbench.domain.TaskDefinition;
import dev.workbench.domain.ToolKind;
import dev.workbench.framework.tool.ResourceVersion;
import dev.workbench.persistence.entity.SourceArtifactEntity;
import dev.workbench.persistence.repository.SourceArtifactRepository;
import dev.workbench.workspace.WorkspaceService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import dev.workbench.domain.TaskEntryKind;
import dev.workbench.store.AgentAssemblyRecordService;
import dev.workbench.store.RunStateStore;
import dev.workbench.store.TaskRecordService;

/**
 * 任务 8：把已有文档、代码差异或测试成果接入待确认计划。
 *
 * <p>来源运行、来源任务版本、目标任务版本和当前工作区授权在接入时校验一次，执行前再核对一次；来源结果摘要变化时拒绝使用。
 */
@Service
public class SourceArtifactService {
  public enum Kind {
    DOCUMENT,
    CODE_DIFF,
    TEST_EVIDENCE
  }

  private final RunStateStore runs;
  private final TaskRecordService tasks;
  private final AgentAssemblyRecordService agents;
  private final SourceArtifactRepository store;
  private final TransactionTemplate tx;
  private final ObjectMapper json;
  private final WorkspaceService workspaces;

  public SourceArtifactService(
      RunStateStore runs,
      TaskRecordService tasks,
      AgentAssemblyRecordService agents,
      SourceArtifactRepository store,
      TransactionTemplate tx,
      ObjectMapper json,
      WorkspaceService workspaces) {
    this.runs = runs;
    this.tasks = tasks;
    this.agents = agents;
    this.store = store;
    this.tx = tx;
    this.json = json;
    this.workspaces = workspaces;
  }

  /** 只接受已结算运行；目标任务必须处于待确认或可执行状态，并保持原有授权边界。 */
  public Artifact attach(
      String targetRunId,
      int expectedTargetTaskVersion,
      String sourceRunId,
      Kind kind,
      int expectedSourceTaskVersion) {
    if (kind == null) throw new IllegalArgumentException("请选择成果类型。");
    RunSnapshot target = runs.runSnapshot(targetRunId);
    RunSnapshot source = runs.runSnapshot(sourceRunId);
    TaskDefinition targetTask = tasks.taskDefinition(target.taskId().toString());
    TaskDefinition sourceTask = tasks.taskDefinition(source.taskId().toString());
    if (target.status() != RunStatus.PENDING)
      throw new IllegalStateException("只有待执行/待确认运行可以接入既有成果，已结束运行不可再修改来源。");
    if (tasks.taskVersion(targetTask.id().toString()) != expectedTargetTaskVersion)
      throw new IllegalStateException("目标任务版本已变化，请重新读取后再接入成果。");
    if (tasks.taskVersion(sourceTask.id().toString()) != expectedSourceTaskVersion)
      throw new IllegalStateException("来源任务版本已变化，请重新确认来源。");
    if (source.status().canCancel())
      throw new IllegalStateException("来源运行尚未结算，不能作为既有成果。");
    if (source.status() == RunStatus.PENDING)
      throw new IllegalStateException("来源运行尚未启动。");
    if (source.status() == RunStatus.CANCELLED || source.status() == RunStatus.INTERRUPTED)
      throw new IllegalStateException("来源运行被取消或中断，不能提供可核验成果。");
    if (kind == Kind.CODE_DIFF
        && !targetTask.writeEnabled()
        && targetTask.entryKind() != dev.workbench.domain.TaskEntryKind.TEST)
      throw new IllegalStateException("代码差异只能接入已授权写入任务或只读测试任务。");
    if (sourceTask.workspaceId() != null) {
      if (!sourceTask.workspaceId().equals(targetTask.workspaceId()))
        throw new IllegalStateException("来源与目标任务不在同一工作区，不能直接接入。");
    }
    if (targetTask.workspaceId() != null && targetTask.fileScopes().isEmpty())
      throw new IllegalStateException("目标任务的当前文件授权为空。");
    if (kind == Kind.CODE_DIFF && sourceTask.workspaceId() == null)
      throw new IllegalStateException("纯文本来源没有可接入的代码差异。");
    if (kind == Kind.DOCUMENT && sourceTask.workspaceId() != null) {
      for (String path : sourceTask.fileScopes())
        if (!targetTask.fileScopes().contains(path))
          throw new IllegalStateException("目标任务未授权来源文档文件：" + path);
    }
    Map<String, String> fileVersions = fileVersions(source, sourceTask, targetTask, kind);
    for (String path : fileVersions.keySet())
      if (!targetTask.fileScopes().contains(path))
        throw new IllegalStateException("来源成果包含目标任务未授权的文件：" + path);
    String content = content(source, sourceTask, kind, fileVersions);
    if (content == null || content.isBlank())
      throw new IllegalStateException("来源运行没有可供继续使用的" + kind.name() + "内容。");
    String sha = sha(kind.name().toLowerCase(Locale.ROOT) + "\n" + content);
    String fileVersionsJson = null;
    if (!fileVersions.isEmpty()) {
      try {
        fileVersionsJson = json.writeValueAsString(fileVersions);
      } catch (JsonProcessingException e) {
        throw new IllegalStateException("来源文件版本无法保存。", e);
      }
    }
    String summary =
        (kind == Kind.DOCUMENT
                ? "来源文档 " + shortId(sourceRunId)
                : kind == Kind.CODE_DIFF
                    ? "来源代码差异 " + shortId(sourceRunId)
                    : "来源测试成果 " + shortId(sourceRunId))
            + "；来源运行状态 " + source.status()
            + "；摘要 " + sha.substring(0, 12);
    final String savedVersionsJson = fileVersionsJson;
    return tx.execute(
        ignored -> {
          if (store.existsByTaskIdAndKindAndSourceRunId(
              targetTask.id().toString(), kind.name(), sourceRunId))
            throw new IllegalStateException("该来源成果已接入，不能重复登记。");
          SourceArtifactEntity row =
              new SourceArtifactEntity(
                  dev.workbench.domain.WorkbenchIds.newId(),
                  targetTask.id().toString(),
                  kind.name(),
                  sourceTask.id().toString(),
                  sourceRunId,
                  expectedSourceTaskVersion,
                  sha,
                  summary,
                  content,
                  savedVersionsJson,
                  Instant.now().toString());
          store.save(row);
          return toArtifact(row, content, true);
        });
  }

  /** 执行前核对来源仍存在、任务版本未变、当前授权仍覆盖来源文件且内容摘要未变。 */
  public void requireCurrent(String taskId) {
    TaskDefinition target = tasks.taskDefinition(taskId);
    for (SourceArtifactEntity row : store.findByTaskIdOrderByCreatedAtAsc(taskId)) {
      RunSnapshot source;
      try {
        source = runs.runSnapshot(row.getSourceRunId());
      } catch (RuntimeException e) {
        throw new IllegalStateException("接入成果的来源运行已不可用。");
      }
      TaskDefinition sourceTask = tasks.taskDefinition(source.taskId().toString());
      if (tasks.taskVersion(sourceTask.id().toString()) != row.getSourceTaskVersion())
        throw new IllegalStateException("接入成果的来源任务版本已变化：" + row.getSummary());
      String current = currentContent(row, source, sourceTask, target);
      String currentSha =
          sha(Kind.valueOf(row.getKind()).name().toLowerCase(Locale.ROOT) + "\n" + current);
      if (!currentSha.equals(row.getArtifactSha256()))
        throw new IllegalStateException(
            "接入成果的来源结果已变化："
                + row.getSummary()
                + "；当前摘要 "
                + currentSha.substring(0, 12)
                + "；接入摘要 "
                + row.getArtifactSha256().substring(0, 12));
    }
  }

  /** 页面预览：当前授权或文件版本失效时隐藏正文，避免展示已撤权内容。 */
  public List<Artifact> find(String taskId) {
    TaskDefinition target = tasks.taskDefinition(taskId);
    return store.findByTaskIdOrderByCreatedAtAsc(taskId).stream()
        .map(
            row -> {
              try {
                RunSnapshot source = runs.runSnapshot(row.getSourceRunId());
                TaskDefinition sourceTask = tasks.taskDefinition(source.taskId().toString());
                String content = currentContent(row, source, sourceTask, target);
                return toArtifact(row, content, true);
              } catch (RuntimeException e) {
                return toArtifact(row, "来源内容已失效或授权已撤销：" + e.getMessage(), false);
              }
            })
        .toList();
  }

  /** 供工作流输入装配；重新核对目标当前授权和文件版本，失效时拒绝装配。 */
  public List<String> constraints(String taskId) {
    TaskDefinition target = tasks.taskDefinition(taskId);
    return store.findByTaskIdOrderByCreatedAtAsc(taskId).stream()
        .map(
            row -> {
              RunSnapshot source = runs.runSnapshot(row.getSourceRunId());
              TaskDefinition sourceTask = tasks.taskDefinition(source.taskId().toString());
              String content = currentContent(row, source, sourceTask, target);
              return "接入成果："
                  + row.getKind()
                  + "；来源运行 "
                  + row.getSourceRunId()
                  + "；"
                  + row.getSummary()
                  + "；内容：\n"
                  + limit(content);
            })
        .toList();
  }

  /** 只从来源运行实际写过的文件生成差异，并绑定写入时的文件版本。 */
  private Map<String, String> fileVersions(
      RunSnapshot source, TaskDefinition sourceTask, TaskDefinition targetTask, Kind kind) {
    if (kind == Kind.DOCUMENT) {
      if (sourceTask.workspaceId() == null) return Map.of();
      var versions = new java.util.TreeMap<String, String>();
      for (String path : sourceTask.fileScopes()) {
        if (!targetTask.fileScopes().contains(path))
          throw new IllegalStateException("目标任务未授权来源文档文件：" + path);
        try {
          versions.put(path, workspaces.read(sourceTask.workspaceId(), path).sha256());
        } catch (RuntimeException e) {
          throw new IllegalStateException("来源文档文件已不可读：" + path);
        }
      }
      return Map.copyOf(versions);
    }
    if (kind != Kind.CODE_DIFF) return Map.of();
    if (sourceTask.workspaceId() == null)
      throw new IllegalStateException("来源任务没有工作区，不能接入代码差异。");
    var versions = new LinkedHashMap<String, String>();
    for (var tool : agents.tools(source.id().toString())) {
      if (!"COMPLETED".equals(String.valueOf(tool.get("STATUS")))) continue;
      if (!ToolKind.WORKSPACE_WRITE.recordName().equals(String.valueOf(tool.get("TOOL_NAME")))) continue;
      Object raw = tool.get("RESOURCE_VERSION");
      if (raw == null) continue;
      for (var entry : ResourceVersion.decode(raw.toString()).entrySet()) {
        String path;
        try {
          path = dev.workbench.workspace.AuthorizedFileSet.normalize(entry.getKey());
        } catch (IllegalArgumentException e) {
          throw new IllegalStateException("来源差异包含无效路径，不能接入。");
        }
        if (!targetTask.fileScopes().contains(path) || !sourceTask.fileScopes().contains(path))
          throw new IllegalStateException("来源差异包含未授权文件，不能接入：" + path);
        String current;
        try {
          current = workspaces.read(sourceTask.workspaceId(), path).sha256();
        } catch (RuntimeException e) {
          throw new IllegalStateException("来源差异文件已不可读：" + path);
        }
        if (!java.security.MessageDigest.isEqual(
            current.getBytes(StandardCharsets.US_ASCII),
            entry.getValue().getBytes(StandardCharsets.US_ASCII)))
          throw new IllegalStateException("来源运行结束后授权文件已变化，不能接入：" + path);
        String previous = versions.putIfAbsent(path, entry.getValue());
        if (previous != null && !previous.equals(entry.getValue()))
          throw new IllegalStateException("来源运行对同一文件产生了不一致的版本证据：" + path);
      }
    }
    if (versions.isEmpty())
      throw new IllegalStateException("来源运行没有实际写过的授权文件差异。");
    return Map.copyOf(versions);
  }

  private String currentContent(
      SourceArtifactEntity row, RunSnapshot source, TaskDefinition sourceTask, TaskDefinition target) {
    Kind kind;
    try {
      kind = Kind.valueOf(row.getKind());
    } catch (IllegalArgumentException e) {
      throw new IllegalStateException("接入成果类型无效。");
    }
    Map<String, String> versions = readVersions(row.getFileVersionsJson());
    for (var entry : versions.entrySet()) {
      if (!target.fileScopes().contains(entry.getKey()))
        throw new IllegalStateException("目标任务已撤销来源文件授权：" + entry.getKey());
      String current;
      try {
        current = workspaces.read(target.workspaceId(), entry.getKey()).sha256();
      } catch (RuntimeException e) {
        throw new IllegalStateException("来源文件已不可读：" + entry.getKey());
      }
      if (!current.equals(entry.getValue()))
        throw new IllegalStateException("来源文件在接入后已变化：" + entry.getKey());
    }
    return content(source, sourceTask, kind, versions);
  }

  private String content(
      RunSnapshot source, TaskDefinition task, Kind kind, Map<String, String> versions) {
    String result = runResult(source.id().toString());
    List<String> paths = versions.keySet().stream().sorted().toList();
    return switch (kind) {
      case DOCUMENT -> {
        StringBuilder value = new StringBuilder();
        for (String path : paths) {
          try {
            var file = workspaces.read(task.workspaceId(), path);
            value.append("\n--- ").append(path).append(" ---\n").append(file.content());
          } catch (RuntimeException e) {
            throw new IllegalStateException("来源授权文件已不可读，不能接入：" + path);
          }
        }
        if (!result.isBlank()) value.append("\n--- 运行结果 ---\n").append(result);
        yield value.toString().strip();
      }
      case CODE_DIFF -> {
        if (task.workspaceId() == null || versions.isEmpty())
          throw new IllegalStateException("来源任务没有可接入的代码差异。");
        String diff = workspaces.diffForPaths(task.workspaceId(), paths);
        if (diff.isBlank()) throw new IllegalStateException("来源授权文件没有可接入的代码差异。");
        yield diff;
      }
      case TEST_EVIDENCE -> {
        String tools = toolsJson(source.id().toString());
        if (result.isBlank() && (tools.isBlank() || tools.equals("[]"))) yield "";
        yield (result + "\n" + tools).strip();
      }
    };
  }

  private Map<String, String> readVersions(String jsonValue) {
    if (jsonValue == null || jsonValue.isBlank()) return Map.of();
    try {
      Map<String, String> parsed =
          json.readValue(
              jsonValue,
              json.getTypeFactory().constructMapType(java.util.TreeMap.class, String.class, String.class));
      for (var entry : parsed.entrySet())
        if (entry.getKey() == null
            || !entry.getValue().matches("[0-9a-f]{64}"))
          throw new IllegalStateException("来源文件版本记录无效。");
      return Map.copyOf(parsed);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("来源文件版本记录无法读取。", e);
    }
  }

  private String runResult(String runId) {
    try {
      var run = runs.run(runId);
      Object value = run.get("RESULT");
      return value == null ? "" : value.toString();
    } catch (RuntimeException ignored) {
      return "";
    }
  }

  private String toolsJson(String runId) {
    try {
      return json.writeValueAsString(agents.tools(runId));
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("来源成果证据无法整理。", e);
    }
  }

  private static String limit(String value) {
    if (value == null) return "";
    String text = value.length() <= 12_000 ? value : value.substring(0, 11_999) + "…";
    return text;
  }

  private static Artifact toArtifact(
      SourceArtifactEntity row, String contentPreview, boolean contentAvailable) {
    return new Artifact(
        row.getId(),
        row.getTaskId(),
        row.getKind(),
        row.getSourceTaskId(),
        row.getSourceRunId(),
        row.getSourceTaskVersion(),
        row.getArtifactSha256(),
        row.getSummary(),
        limit(contentPreview),
        contentAvailable,
        row.getCreatedAt());
  }

  private static String shortId(String id) {
    return id.length() <= 8 ? id : id.substring(0, 8);
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

  public record Artifact(
      String id,
      String taskId,
      String kind,
      String sourceTaskId,
      String sourceRunId,
      int sourceTaskVersion,
      String artifactSha256,
      String summary,
      String contentPreview,
      boolean contentAvailable,
      String createdAt) {}
}
