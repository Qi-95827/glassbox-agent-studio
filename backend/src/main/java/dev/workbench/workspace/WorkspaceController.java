package dev.workbench.workspace;

import org.springframework.web.bind.annotation.*;

/** 本地工作区接口；创建工作区必须由用户显式触发。 */
@RestController
@RequestMapping("/api/workspaces")
public class WorkspaceController {
  private final WorkspaceService service;
  private final ApprovalService approvals;
  private final WorkspaceCommitService commits;
  private final WorkspaceFileCatalog catalog;
  private final GitDeliveryService deliveries;
  private final dev.workbench.ownership.OwnershipService ownership;
  private final dev.workbench.ownership.RepositoryAuthorizationService repositoryAuthorization;

  public WorkspaceController(
      WorkspaceService service,
      ApprovalService approvals,
      WorkspaceCommitService commits,
      WorkspaceFileCatalog catalog,
      GitDeliveryService deliveries,
      dev.workbench.ownership.OwnershipService ownership,
      dev.workbench.ownership.RepositoryAuthorizationService repositoryAuthorization) {
    this.service = service;
    this.approvals = approvals;
    this.commits = commits;
    this.catalog = catalog;
    this.deliveries = deliveries;
    this.ownership = ownership;
    this.repositoryAuthorization = repositoryAuthorization;
  }

  private void require(String workspaceId) {
    ownership.requireWorkspace(workspaceId);
  }

  public record CreateWorkspace(
      String repositoryPath, String baseBranch, String workBranch, Boolean autoGrantForAdmin) {
    public CreateWorkspace(String repositoryPath, String baseBranch) {
      this(repositoryPath, baseBranch, null);
    }

    public CreateWorkspace(String repositoryPath, String baseBranch, String workBranch) {
      this(repositoryPath, baseBranch, workBranch, null);
    }
  }

  public record CreateIdeaWorkspace(java.util.List<String> files) {}

  public record UpdateFile(String path, String expectedHash, String content) {}

  public record CreateFile(String path, String content) {}

  public record ReviewUntracked(String path, String sha256) {}

  public record ApprovalDecision(ApprovalService.Decision decision) {}

  public record CommitRequest(String approvalId, String message) {}

  public record DeliveryRequest(String kind, String remote, String target, String note) {}

  public record DeliveryDecision(boolean approve) {}

  @GetMapping("/{id}/commits")
  public Object commits(@PathVariable String id) {
    require(id);
    return commits.list(id);
  }

  @PostMapping("/{id}/commits")
  public Object commit(@PathVariable String id, @RequestBody CommitRequest request) {
    require(id);
    return commits.commit(id, request.approvalId(), request.message());
  }

  @GetMapping
  public Object list() {
    return service.listOwned();
  }

  @PostMapping
  public Object create(@RequestBody CreateWorkspace request) {
    grantRepositoryForAdmin(request);
    return service.create(request.repositoryPath(), request.baseBranch(), request.workBranch());
  }

  /**
   * 管理员一键导入：为当前管理员登记目标仓库后再创建独立 worktree。
   *
   * <p>安全边界：只有经过服务端会话确认的管理员可以触发；普通用户携带该标记会被拒绝（403），且不会写入
   * 任何授权记录或工作区。未携带该字段的旧请求体保持原有行为，完全不受影响。
   */
  private void grantRepositoryForAdmin(CreateWorkspace request) {
    if (!Boolean.TRUE.equals(request.autoGrantForAdmin())) return;
    // 管理员身份只来自服务端会话；普通用户在这里就被拒绝（403），不会产生授权或工作区。
    repositoryAuthorization.requireCurrentAdmin();
    // 先做只读校验，路径无效或不是仓库根目录时不落库授权。
    service.requireImportableRepository(request.repositoryPath());
    // grant 幂等：已授权时直接返回既有记录，不产生重复行。
    repositoryAuthorization.grantToCurrentAdmin(request.repositoryPath());
  }

  @PostMapping("/from-idea")
  public WorkspaceService.Workspace createFromIdea(@RequestBody CreateIdeaWorkspace request) {
    return service.createIdeaWorkspace(request.files());
  }

  @GetMapping("/{id}/status")
  public Object status(@PathVariable String id) {
    require(id);
    return service.status(id);
  }

  @GetMapping("/{id}/file")
  public Object file(@PathVariable String id, @RequestParam String path) {
    require(id);
    return service.read(id, path);
  }

  @GetMapping("/{id}/files")
  public org.springframework.http.ResponseEntity<WorkspaceFileCatalog.Candidates> files(
      @PathVariable String id, @RequestParam(defaultValue = "") String keyword) {
    require(id);
    return org.springframework.http.ResponseEntity.ok()
        .cacheControl(org.springframework.http.CacheControl.noStore())
        .body(catalog.find(id, keyword));
  }

  @PutMapping("/{id}/file")
  public Object write(@PathVariable String id, @RequestBody UpdateFile request) {
    require(id);
    return service.write(id, request.path(), request.expectedHash(), request.content());
  }

  /** 新建文本文件；原子创建并登记，不能覆盖现有文件。 */
  @PostMapping("/{id}/file")
  public Object createFile(@PathVariable String id, @RequestBody CreateFile request) {
    require(id);
    return service.createUserFile(id, request.path(), request.content());
  }

  @GetMapping("/{id}/untracked")
  public Object untracked(@PathVariable String id) {
    require(id);
    return service.untrackedFiles(id);
  }

  /** 用户逐项复核未跟踪文件；摘要不符时拒绝。 */
  @PostMapping("/{id}/untracked/review")
  public Object reviewUntracked(@PathVariable String id, @RequestBody ReviewUntracked request) {
    require(id);
    return service.declareUntracked(id, request.path(), request.sha256());
  }

  @GetMapping("/{id}/deliveries")
  public Object deliveries(@PathVariable String id) {
    require(id);
    return deliveries.list(id);
  }

  @PostMapping("/{id}/deliveries")
  public Object prepareDelivery(@PathVariable String id, @RequestBody DeliveryRequest request) {
    require(id);
    return deliveries.prepare(
        id, request.kind(), request.remote(), request.target(), request.note());
  }

  @PostMapping("/deliveries/{id}/decision")
  public Object decideDelivery(@PathVariable String id, @RequestBody DeliveryDecision request) {
    require(deliveries.get(id).workspaceId().toString());
    return deliveries.decide(id, request.approve());
  }

  @PostMapping("/deliveries/{id}/execute")
  public Object executeDelivery(@PathVariable String id) {
    require(deliveries.get(id).workspaceId().toString());
    return deliveries.execute(id);
  }

  @GetMapping("/{id}/approvals")
  public Object approvals(@PathVariable String id) {
    require(id);
    return approvals.list(id);
  }

  @PostMapping("/{id}/approvals")
  public Object propose(@PathVariable String id) {
    require(id);
    return approvals.propose(id);
  }

  @PostMapping("/approvals/{id}/decision")
  public Object decide(@PathVariable String id, @RequestBody ApprovalDecision request) {
    if (request.decision() == null) throw new IllegalArgumentException("请选择批准或拒绝。");
    return approvals.decide(id, request.decision());
  }
}
