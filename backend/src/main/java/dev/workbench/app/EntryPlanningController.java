package dev.workbench.app;

import dev.workbench.domain.EntryRouteService;
import dev.workbench.domain.TaskEntryKind;
import dev.workbench.task.PlanProposalService;
import dev.workbench.task.RecoveryLeaseService;
import dev.workbench.task.RunRecordService;
import dev.workbench.task.RunSnapshotService;
import dev.workbench.task.SourceArtifactService;
import dev.workbench.workspace.WorkspaceAuthorizationService;
import java.util.List;
import org.springframework.web.bind.annotation.*;

/** 任务 8：规则路由、候选计划、既有成果、目录授权和周期快照的入口。 */
@RestController
@RequestMapping("/api")
public class EntryPlanningController {
  private final EntryRouteService routes;
  private final PlanProposalService proposals;
  private final SourceArtifactService sources;
  private final WorkspaceAuthorizationService authorization;
  private final RunSnapshotService snapshots;
  private final RecoveryLeaseService leases;
  private final RunRecordService runs;
  private final dev.workbench.ownership.OwnershipService ownership;

  public EntryPlanningController(
      EntryRouteService routes,
      PlanProposalService proposals,
      SourceArtifactService sources,
      WorkspaceAuthorizationService authorization,
      RunSnapshotService snapshots,
      RecoveryLeaseService leases,
      RunRecordService runs,
      dev.workbench.ownership.OwnershipService ownership) {
    this.routes = routes;
    this.proposals = proposals;
    this.sources = sources;
    this.authorization = authorization;
    this.snapshots = snapshots;
    this.leases = leases;
    this.runs = runs;
    this.ownership = ownership;
  }

  public record RouteRequest(
      TaskEntryKind entryKind,
      String workspaceId,
      Boolean writeEnabled,
      List<String> fileScopes,
      List<String> directoryScopes,
      String workflowTemplate) {}

  @PostMapping("/entry-routes/preview")
  Object preview(@RequestBody RouteRequest request) {
    return routes.route(
        request.entryKind(),
        request.workspaceId(),
        Boolean.TRUE.equals(request.writeEnabled()),
        request.fileScopes() == null ? List.of() : request.fileScopes(),
        request.directoryScopes() == null ? List.of() : request.directoryScopes(),
        request.workflowTemplate());
  }

  @GetMapping("/tasks/{taskId}/entry-route")
  Object taskRoute(@PathVariable String taskId) {
    return routes.find(taskId);
  }

  @GetMapping("/runs/{runId}/plan-proposals")
  Object planProposals(@PathVariable String runId) {
    ownership.requireRun(runId);
    return proposals.list(runId);
  }

  public record ProposeRequest(
      List<PlanProposalService.NodeChange> changes, String expectedBaseSha256) {}

  @PostMapping("/runs/{runId}/plan-proposals")
  Object propose(@PathVariable String runId, @RequestBody ProposeRequest request) {
    ownership.requireRun(runId);
    return proposals.propose(runId, request.changes(), request.expectedBaseSha256());
  }

  @PostMapping("/runs/{runId}/plan-proposals/{id}/apply")
  Object applyProposal(@PathVariable String runId, @PathVariable String id) {
    ownership.requireRun(runId);
    return proposals.apply(runId, id);
  }

  @GetMapping("/runs/{runId}/source-artifacts")
  Object sourceArtifacts(@PathVariable String runId) {
    ownership.requireRun(runId);
    String taskId = runs.runSnapshot(runId).taskId().toString();
    return sources.find(taskId);
  }

  public record AttachSource(
      String sourceRunId,
      SourceArtifactService.Kind kind,
      Integer expectedTargetTaskVersion,
      Integer expectedSourceTaskVersion) {}

  @PostMapping("/runs/{runId}/source-artifacts")
  Object attachSource(@PathVariable String runId, @RequestBody AttachSource request) {
    ownership.requireRun(runId);
    ownership.requireRun(request.sourceRunId());
    if (request.expectedTargetTaskVersion() == null || request.expectedSourceTaskVersion() == null)
      throw new IllegalArgumentException("请携带读取页面时的目标任务和来源任务版本。");
    int targetVersion = runs.taskVersion(runs.runSnapshot(runId).taskId().toString());
    if (targetVersion != request.expectedTargetTaskVersion())
      throw new IllegalStateException("目标任务版本已变化，请重新读取后再接入成果。");
    String taskId = runs.runSnapshot(runId).taskId().toString();
    return sources.attach(
        runId,
        request.expectedTargetTaskVersion(),
        request.sourceRunId(),
        request.kind(),
        request.expectedSourceTaskVersion());
  }

  @GetMapping("/runs/{runId}/workspace-scope")
  Object workspaceScope(@PathVariable String runId) {
    ownership.requireRun(runId);
    return authorization.find(runs.runSnapshot(runId).taskId().toString());
  }

  public record Revoke(String directory) {}

  @PostMapping("/runs/{runId}/workspace-scope/revoke")
  Object revoke(@PathVariable String runId, @RequestBody Revoke request) {
    ownership.requireRun(runId);
    return authorization.revoke(runs.runSnapshot(runId).taskId().toString(), request.directory());
  }

  @GetMapping("/runs/{runId}/snapshots")
  Object snapshots(@PathVariable String runId) {
    ownership.requireRun(runId);
    return snapshots.find(runId);
  }

  @GetMapping("/runs/{runId}/recovery-lease")
  Object lease(@PathVariable String runId) {
    ownership.requireRun(runId);
    return leases.find(runId);
  }
}
