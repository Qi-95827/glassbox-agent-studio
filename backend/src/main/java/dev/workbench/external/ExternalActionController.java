package dev.workbench.external;

import dev.workbench.external.ExternalActionService;
import java.util.List;
import org.springframework.web.bind.annotation.*;

/** 外部动作准备、派发标记和只读对账。 */
@RestController
@RequestMapping("/api/external-actions")
public class ExternalActionController {
  private final ExternalActionService actions;

  public ExternalActionController(ExternalActionService actions) {
    this.actions = actions;
  }

  public record PrepareRequest(
      String runId,
      String actionKey,
      String kind,
      String approvalRef,
      String target,
      String targetVersion,
      String payload) {}

  @GetMapping
  List<ExternalActionService.Action> list(@RequestParam(required = false) String runId) {
    return runId == null ? actions.list() : actions.findByRun(runId);
  }

  @GetMapping("/{id}")
  ExternalActionService.Action get(@PathVariable String id) {
    return actions.get(id);
  }

  @PostMapping("/prepare")
  ExternalActionService.Action prepare(@RequestBody PrepareRequest request) {
    return actions.prepare(
        request.runId(),
        request.actionKey(),
        request.kind(),
        request.approvalRef(),
        request.target(),
        request.targetVersion(),
        request.payload());
  }

  @PostMapping("/{id}/dispatch")
  ExternalActionService.Action dispatch(@PathVariable String id) {
    return actions.markDispatched(actions.get(id).actionKey());
  }

  /** 只查询和补记证据，不会重放外部写入。 */
  @PostMapping("/{id}/reconcile")
  ExternalActionService.Action reconcile(@PathVariable String id) {
    return actions.reconcile(actions.get(id).actionKey());
  }
}
