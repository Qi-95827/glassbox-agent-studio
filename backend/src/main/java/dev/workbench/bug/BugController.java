package dev.workbench.bug;

import org.springframework.web.bind.annotation.*;

/** 模拟 Bug 和显式发起的容器验证接口。 */
@RestController
@RequestMapping("/api/bugs")
public class BugController {
  private final BugService service;

  public BugController(BugService service) {
    this.service = service;
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
    return service.checks(workspaceId);
  }

  /** 仅查询明确关联运行的测试证据。 */
  @GetMapping("/checks/by-run")
  public Object checksForRun(@RequestParam String runId) {
    return service.checksForRun(runId);
  }

  @GetMapping("/feedback")
  public Object feedbackHistory(@RequestParam String workspaceId) {
    return service.feedbackHistory(workspaceId);
  }

  @PostMapping("/{id}/checks")
  public Object check(@PathVariable String id, @RequestBody CheckRequest request) {
    if (request.kind() == null || request.workspaceId() == null)
      throw new IllegalArgumentException("请选择工作区和验证类型。");
    return service.run(id, request.workspaceId(), request.kind());
  }

  @PostMapping("/{id}/feedback")
  public Object feedback(@PathVariable String id, @RequestBody FeedbackRequest request) {
    if (request.status() == null || request.workspaceId() == null)
      throw new IllegalArgumentException("请选择工作区和反馈状态。");
    return service.feedback(id, request.workspaceId(), request.status(), request.note());
  }
}
