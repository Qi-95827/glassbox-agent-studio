package dev.workbench.tool;

import dev.workbench.framework.tool.ToolApprovalAction;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Gate 5.5 审批查询与用户决策；所有访问先核对运行归属。 */
@RestController
@RequestMapping("/api/runs/{runId}/tool-approvals")
public class ToolApprovalController {
  private final ToolApprovalService approvals;
  private final dev.workbench.ownership.OwnershipService ownership;

  public ToolApprovalController(
      ToolApprovalService approvals, dev.workbench.ownership.OwnershipService ownership) {
    this.approvals = approvals;
    this.ownership = ownership;
  }

  public record DecisionRequest(
      ToolApprovalAction action, String editedArgsJson, String reason) {}

  @GetMapping
  public List<ToolApprovalService.ApprovalView> list(@PathVariable String runId) {
    ownership.requireRun(runId);
    return approvals.list(runId);
  }

  @GetMapping("/{approvalId}")
  public ToolApprovalService.ApprovalView get(
      @PathVariable String runId, @PathVariable String approvalId) {
    ownership.requireRun(runId);
    return approvals.find(runId, approvalId);
  }

  @PostMapping("/{approvalId}/decision")
  public ToolApprovalService.ApprovalView decide(
      @PathVariable String runId,
      @PathVariable String approvalId,
      @RequestBody DecisionRequest request) {
    ownership.requireRun(runId);
    return approvals.decide(
        runId,
        approvalId,
        request == null ? null : request.action(),
        request == null ? null : request.editedArgsJson(),
        request == null ? null : request.reason());
  }

  @org.springframework.web.bind.annotation.ExceptionHandler({
    IllegalArgumentException.class,
    IllegalStateException.class
  })
  @org.springframework.web.bind.annotation.ResponseStatus(
      org.springframework.http.HttpStatus.BAD_REQUEST)
  Map<String, String> invalid(RuntimeException error) {
    return Map.of("message", error.getMessage() == null ? "审批请求无效。" : error.getMessage());
  }
}
