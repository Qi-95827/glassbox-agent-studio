package dev.workbench.bug;

import org.springframework.web.bind.annotation.*;

/** 模拟 Bug 和显式发起的容器验证接口。 */
@RestController
@RequestMapping("/api/bugs")
public class BugController {
  private final BugService service;
  private final BugIntakeService intakes;
  private final dev.workbench.ownership.OwnershipService ownership;

  public BugController(
      BugService service,
      BugIntakeService intakes,
      dev.workbench.ownership.OwnershipService ownership) {
    this.service = service;
    this.intakes = intakes;
    this.ownership = ownership;
  }

  public record CheckRequest(String workspaceId, BugService.CheckKind kind) {}

  public record FeedbackRequest(
      String workspaceId, BugService.FeedbackStatus status, String note) {}

  @GetMapping
  public Object list() {
    return service.list();
  }

  @GetMapping("/checks")
  public Object checks(@RequestParam String workspaceId) {
    ownership.requireWorkspace(workspaceId);
    return service.checks(workspaceId);
  }

  /** 仅查询明确关联运行的测试证据。 */
  @GetMapping("/checks/by-run")
  public Object checksForRun(@RequestParam String runId) {
    ownership.requireRun(runId);
    return service.checksForRun(runId);
  }

  @GetMapping("/feedback")
  public Object feedbackHistory(@RequestParam String workspaceId) {
    ownership.requireWorkspace(workspaceId);
    return service.feedbackHistory(workspaceId);
  }

  /** 原始描述、来源版本与分诊记录；按工作区倒序。 */
  @GetMapping("/intakes")
  public Object intakes(@RequestParam String workspaceId) {
    ownership.requireWorkspace(workspaceId);
    return intakes.history(workspaceId);
  }

  /** 交付视图：只读组合原始描述、分诊、检查和当前结论。 */
  @GetMapping("/delivery")
  public Object delivery(@RequestParam String runId) {
    ownership.requireRun(runId);
    return intakes.delivery(runId);
  }

  @PostMapping("/{id}/checks")
  public Object check(@PathVariable String id, @RequestBody CheckRequest request) {
    ownership.requireWorkspace(request.workspaceId());
    if (request.kind() == null || request.workspaceId() == null)
      throw new IllegalArgumentException("请选择工作区和验证类型。");
    return service.run(id, request.workspaceId(), request.kind());
  }

  @PostMapping("/{id}/feedback")
  public Object feedback(@PathVariable String id, @RequestBody FeedbackRequest request) {
    ownership.requireWorkspace(request.workspaceId());
    if (request.status() == null || request.workspaceId() == null)
      throw new IllegalArgumentException("请选择工作区和反馈状态。");
    return service.feedback(id, request.workspaceId(), request.status(), request.note());
  }
}
