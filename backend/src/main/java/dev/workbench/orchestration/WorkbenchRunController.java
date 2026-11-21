package dev.workbench.orchestration;

import dev.workbench.infrastructure.agentscope.AgentSessionService;
import dev.workbench.workspace.RunHandoverService;
import java.util.Map;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/** 工作台运行入口：运行查询、计划确认、暂停/继续、取消、恢复、交接与补充信息。 */
@RestController
@RequestMapping("/api")
public class WorkbenchRunController {
  private final WorkbenchTaskService taskService;
  private final RunService service;
  private final TaskSupplementService supplements;
  private final AgentSessionService sessions;
  private final RunHandoverService handovers;
  private final dev.workbench.ownership.OwnershipService ownership;

  public WorkbenchRunController(
      WorkbenchTaskService taskService,
      RunService service,
      TaskSupplementService supplements,
      AgentSessionService sessions,
      RunHandoverService handovers,
      dev.workbench.ownership.OwnershipService ownership) {
    this.taskService = taskService;
    this.service = service;
    this.supplements = supplements;
    this.sessions = sessions;
    this.handovers = handovers;
    this.ownership = ownership;
  }

  @GetMapping("/runs/{id}")
  Object run(@PathVariable String id, @RequestParam(defaultValue = "true") boolean includeEvents) {
    return taskService.run(id, includeEvents);
  }

  @PostMapping("/runs/{id}/plans/{planVersionId}/approve")
  Object approvePlan(@PathVariable String id, @PathVariable java.util.UUID planVersionId) {
    ownership.requireRun(id);
    service.approvePlan(id, planVersionId);
    return Map.of("accepted", true);
  }

  @PostMapping("/runs/{id}/cancel")
  Object cancel(@PathVariable String id) {
    ownership.requireRun(id);
    service.cancel(id);
    return Map.of("accepted", true);
  }

  @GetMapping("/runs/{id}/skills")
  Object skills(@PathVariable String id) {
    return taskService.skills(id);
  }

  @GetMapping("/runs/{id}/memory-load")
  Object memoryLoad(@PathVariable String id) {
    return taskService.memoryLoad(id);
  }

  @GetMapping("/runs/{id}/agent-sessions")
  Object agentSessions(@PathVariable String id) {
    ownership.requireRun(id);
    return sessions.forRun(id);
  }

  @GetMapping("/runs/{id}/tool-grants")
  Object toolGrants(@PathVariable String id) {
    return taskService.toolGrants(id);
  }

  @PostMapping("/runs/{id}/pause")
  Object pause(@PathVariable String id) {
    ownership.requireRun(id);
    service.pause(id);
    return Map.of("accepted", true);
  }

  @PostMapping("/runs/{id}/resume")
  Object resume(@PathVariable String id) {
    ownership.requireRun(id);
    service.resume(id);
    return Map.of("accepted", true);
  }

  public record HandoverRequest(String note) {}

  @GetMapping("/runs/{id}/handover")
  Object handover(@PathVariable String id) {
    return taskService.handover(id);
  }

  @PostMapping("/runs/{id}/handover")
  Object takeHandover(
      @PathVariable String id, @RequestBody(required = false) HandoverRequest request) {
    ownership.requireRun(id);
    return handovers.take(id, request == null ? null : request.note());
  }

  @PostMapping("/runs/{id}/handover/return")
  Object returnHandover(
      @PathVariable String id, @RequestBody(required = false) HandoverRequest request) {
    ownership.requireRun(id);
    return handovers.returnControl(id, request == null ? null : request.note());
  }

  @PostMapping("/runs/{id}/recover/{checkpoint}")
  Object recover(@PathVariable String id, @PathVariable String checkpoint) {
    ownership.requireRun(id);
    return Map.of("runId", service.recover(id, checkpoint));
  }

  /** 任务 2：提交补充信息。校验失败返回 400 与原因，运行保持阻塞。 */
  @PostMapping("/runs/{id}/supplements/{requestId}")
  Object submitSupplement(
      @PathVariable String id,
      @PathVariable String requestId,
      @RequestBody SupplementSubmission request) {
    ownership.requireRun(id);
    if (request.expectedTaskVersion() == null) throw new IllegalArgumentException("请携带读取页面时的任务版本。");
    var applied =
        supplements.apply(
            id, requestId, request.expectedTaskVersion(), request.path(), request.content());
    return Map.of(
        "accepted", true, "taskVersion", applied.newTaskVersion(), "runStatus", "PENDING");
  }

  /** 补充提交体：期望任务版本、测试文件路径与内容。 */
  public record SupplementSubmission(Integer expectedTaskVersion, String path, String content) {}

  @ExceptionHandler(EmptyResultDataAccessException.class)
  @ResponseStatus(HttpStatus.NOT_FOUND)
  Object notFound() {
    return Map.of("message", "记录不存在。");
  }

  @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
  @ResponseStatus(HttpStatus.BAD_REQUEST)
  Object invalid(RuntimeException e) {
    return Map.of("message", e.getMessage());
  }
}
