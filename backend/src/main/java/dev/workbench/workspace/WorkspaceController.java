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

  public WorkspaceController(
      WorkspaceService service,
      ApprovalService approvals,
      WorkspaceCommitService commits,
      WorkspaceFileCatalog catalog) {
    this.service = service;
    this.approvals = approvals;
    this.commits = commits;
    this.catalog = catalog;
  }

  public record CreateWorkspace(String repositoryPath, String baseBranch) {}

  public record UpdateFile(String path, String expectedHash, String content) {}

  public record ApprovalDecision(ApprovalService.Decision decision) {}

  public record CommitRequest(String approvalId, String message) {}

  @GetMapping("/{id}/commits")
  public Object commits(@PathVariable String id) {
    return commits.list(id);
  }

  @PostMapping("/{id}/commits")
  public Object commit(@PathVariable String id, @RequestBody CommitRequest request) {
    return commits.commit(id, request.approvalId(), request.message());
  }

  @GetMapping
  public Object list() {
    return service.list();
  }

  @PostMapping
  public Object create(@RequestBody CreateWorkspace request) {
    return service.create(request.repositoryPath(), request.baseBranch());
  }

  @GetMapping("/{id}/status")
  public Object status(@PathVariable String id) {
    return service.status(id);
  }

  @GetMapping("/{id}/file")
  public Object file(@PathVariable String id, @RequestParam String path) {
    return service.read(id, path);
  }

  @GetMapping("/{id}/files")
  public org.springframework.http.ResponseEntity<WorkspaceFileCatalog.Candidates> files(
      @PathVariable String id, @RequestParam(defaultValue = "") String keyword) {
    return org.springframework.http.ResponseEntity.ok()
        .cacheControl(org.springframework.http.CacheControl.noStore())
        .body(catalog.find(id, keyword));
  }

  @PutMapping("/{id}/file")
  public Object write(@PathVariable String id, @RequestBody UpdateFile request) {
    return service.write(id, request.path(), request.expectedHash(), request.content());
  }

  @GetMapping("/{id}/approvals")
  public Object approvals(@PathVariable String id) {
    return approvals.list(id);
  }

  @PostMapping("/{id}/approvals")
  public Object propose(@PathVariable String id) {
    return approvals.propose(id);
  }

  @PostMapping("/approvals/{id}/decision")
  public Object decide(@PathVariable String id, @RequestBody ApprovalDecision request) {
    if (request.decision() == null) throw new IllegalArgumentException("请选择批准或拒绝。");
    return approvals.decide(id, request.decision());
  }
}
