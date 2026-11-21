package dev.workbench.privateagent;

import static dev.workbench.privateagent.PrivateComponentSupport.normalizeExtension;
import static dev.workbench.privateagent.PrivateComponentSupport.readList;
import static dev.workbench.privateagent.PrivateComponentSupport.requireEditable;
import static dev.workbench.privateagent.PrivateComponentSupport.sha256;
import static dev.workbench.privateagent.PrivateComponentSupport.text;
import static dev.workbench.privateagent.PrivateComponentSupport.write;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.workbench.domain.ExecutionScopeResolver;
import dev.workbench.domain.WorkbenchIds;
import dev.workbench.domain.WorkbenchTime;
import dev.workbench.persistence.entity.PrivateToolEntity;
import dev.workbench.persistence.entity.PrivateToolVersionEntity;
import dev.workbench.persistence.repository.PrivateToolRepository;
import dev.workbench.persistence.repository.PrivateToolVersionRepository;
import dev.workbench.privateagent.PrivateComponentModels.ComponentRef;
import dev.workbench.privateagent.PrivateComponentModels.ToolRequest;
import dev.workbench.privateagent.PrivateComponentModels.ToolView;
import java.util.List;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 私有工具配置的草稿、发布版本与归档；只保存声明，不授予额外运行权限。 */
@Service
public class PrivateToolConfigService {
  private static final java.util.Set<String> TOOL_EXECUTORS =
      java.util.Set.of("READ", "SEARCH");

  private final PrivateToolRepository tools;
  private final PrivateToolVersionRepository toolVersions;
  private final ExecutionScopeResolver scopes;
  private final ObjectMapper json;

  public PrivateToolConfigService(
      PrivateToolRepository tools,
      PrivateToolVersionRepository toolVersions,
      ExecutionScopeResolver scopes,
      ObjectMapper json) {
    this.tools = tools;
    this.toolVersions = toolVersions;
    this.scopes = scopes;
    this.json = json;
  }

  public List<ToolView> tools() {
    return tools.findAllByOwnerScopeKeyOrderByUpdatedAtDesc(scope()).stream()
        .map(this::toolView)
        .toList();
  }

  @Transactional
  public ToolView createTool(ToolRequest request) {
    validateTool(request);
    var row =
        new PrivateToolEntity(
            WorkbenchIds.newId(),
            scope(),
            request.name().strip(),
            WorkbenchTime.now());
    apply(row, request);
    return toolView(tools.saveAndFlush(row));
  }

  @Transactional
  public ToolView updateTool(String id, ToolRequest request) {
    var row = ownTool(id);
    requireEditable(row.getStatus());
    validateTool(request);
    apply(row, request);
    return toolView(tools.saveAndFlush(row));
  }

  @Transactional
  public ToolView publishTool(String id) {
    var row = ownTool(id);
    requireEditable(row.getStatus());
    int version = row.getCurrentVersion() + 1;
    String now = WorkbenchTime.now();
    toolVersions.saveAndFlush(
        new PrivateToolVersionEntity(
            row.getId(),
            version,
            row.getName(),
            row.getDescription(),
            row.getExecutorKind(),
            row.getFileExtensionsJson(),
            toolSpecSha(row),
            now));
    row.setCurrentVersion(version);
    row.setStatus("PUBLISHED");
    row.setUpdatedAt(now);
    return toolView(tools.saveAndFlush(row));
  }

  @Transactional
  public ToolView archiveTool(String id) {
    var row = ownTool(id);
    row.setStatus("ARCHIVED");
    row.setArchivedAt(WorkbenchTime.now());
    row.setUpdatedAt(row.getArchivedAt());
    return toolView(tools.saveAndFlush(row));
  }

  public List<ToolView> toolVersions(String id) {
    ownTool(id);
    return toolVersions.findByToolIdOrderByVersionDesc(id).stream()
        .map(
            row ->
                toolView(
                    row.getToolId(),
                    row.getName(),
                    row.getDescription(),
                    row.getExecutorKind(),
                    readList(json, row.getFileExtensionsJson()),
                    "PUBLISHED",
                    row.getVersion(),
                    false,
                    null,
                    row.getCreatedAt()))
        .toList();
  }

  /** Agent 发布时固定引用版本；归属或发布状态不符即拒绝。 */
  PrivateToolVersionEntity resolveVersion(ComponentRef ref) {
    if (ref == null || ref.id() == null) throw new IllegalArgumentException("工具引用为空。");
    var tool =
        tools
            .findByIdAndOwnerScopeKey(ref.id(), scope())
            .orElseThrow(() -> new IllegalArgumentException("工具不存在或无权访问：" + ref.id()));
    if (!"PUBLISHED".equals(tool.getStatus()))
      throw new IllegalArgumentException("工具未发布：" + tool.getName());
    int version = ref.version() == null ? tool.getCurrentVersion() : ref.version();
    return toolVersions
        .findByToolIdAndVersion(ref.id(), version)
        .orElseThrow(
            () -> new IllegalArgumentException("工具版本不存在：" + ref.id() + " v" + version));
  }

  private ToolView toolView(PrivateToolEntity row) {
    List<String> extensions = readList(json, row.getFileExtensionsJson());
    boolean changed =
        row.getCurrentVersion() < 1
            || !toolSpecSha(row)
                .equals(
                    toolVersions
                        .findByToolIdAndVersion(row.getId(), row.getCurrentVersion())
                        .map(PrivateToolVersionEntity::getSpecSha256)
                        .orElse(""));
    return toolView(
        row.getId(),
        row.getName(),
        row.getDescription(),
        row.getExecutorKind(),
        extensions,
        row.getStatus(),
        row.getCurrentVersion(),
        changed,
        row.getArchivedAt(),
        row.getUpdatedAt());
  }

  private ToolView toolView(
      String id,
      String name,
      String description,
      String executorKind,
      List<String> extensions,
      String status,
      int version,
      boolean changed,
      String archivedAt,
      String updatedAt) {
    return new ToolView(
        id,
        name,
        description,
        executorKind,
        "SEARCH".equals(executorKind) ? "授权搜索" : "授权读取",
        extensions,
        status,
        version,
        changed,
        archivedAt,
        updatedAt);
  }

  private void apply(PrivateToolEntity row, ToolRequest request) {
    row.setName(request.name().strip());
    row.setDescription(request.description().strip());
    row.setExecutorKind(request.executorKind());
    List<String> extensions =
        request.fileExtensions() == null
            ? List.of()
            : request.fileExtensions().stream()
                .map(PrivateComponentSupport::normalizeExtension)
                .distinct()
                .toList();
    row.setFileExtensionsJson(write(json, extensions));
    row.setDraftSha256(toolSpecSha(row));
    row.setUpdatedAt(WorkbenchTime.now());
  }

  private void validateTool(ToolRequest request) {
    if (request == null) throw new IllegalArgumentException("工具配置不能为空。");
    text(request.name(), 120, "工具名称");
    text(request.description(), 300, "工具说明");
    if (!TOOL_EXECUTORS.contains(request.executorKind()))
      throw new IllegalArgumentException("首批私有工具只允许受信读取或授权搜索执行器。");
    if (request.fileExtensions() == null || request.fileExtensions().isEmpty())
      throw new IllegalArgumentException("请至少填写一个允许的文件扩展名，例如 .jsx。");
    if (request.fileExtensions().size() > 10)
      throw new IllegalArgumentException("文件扩展名最多 10 个。");
  }

  private PrivateToolEntity ownTool(String id) {
    return tools
        .findByIdAndOwnerScopeKey(id, scope())
        .orElseThrow(() -> new EmptyResultDataAccessException(1));
  }

  private String scope() {
    return scopes.current().ownerScopeKey();
  }

  private static String toolSpecSha(PrivateToolEntity row) {
    return toolSpecSha(
        row.getId(),
        row.getName(),
        row.getDescription(),
        row.getExecutorKind(),
        row.getFileExtensionsJson());
  }

  private static String toolSpecSha(
      String id, String name, String description, String kind, String extensions) {
    return sha256(
        String.join("\n", "private-tool-v1", id, name, description, kind, extensions));
  }
}
