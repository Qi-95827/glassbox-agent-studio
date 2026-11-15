package dev.workbench.app;

import dev.workbench.agent.AgentSessionService;
import dev.workbench.memory.MemoryService;
import dev.workbench.orchestration.CreateTaskRequest;
import dev.workbench.orchestration.RunService;
import dev.workbench.orchestration.plan.PlanRepository;
import dev.workbench.task.RunRepository;
import java.util.List;
import java.util.Map;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class WorkbenchController {
  private final RunRepository repo;
  private final RunService service;
  private final PlanRepository plans;
  private final MemoryService memories;
  private final AgentSessionService sessions;

  public WorkbenchController(
      RunRepository repo,
      RunService service,
      PlanRepository plans,
      MemoryService memories,
      AgentSessionService sessions) {
    this.repo = repo;
    this.service = service;
    this.plans = plans;
    this.memories = memories;
    this.sessions = sessions;
  }

  @GetMapping("/tasks")
  Object tasks() {
    return repo.tasks();
  }

  @PostMapping("/tasks")
  Object create(@RequestBody CreateTaskRequest request) {
    return Map.of("runId", service.create(request));
  }

  @GetMapping("/runs/{id}")
  Object run(@PathVariable String id, @RequestParam(defaultValue = "true") boolean includeEvents) {
    var run = repo.run(id);
    Map<String, Object> detail = new java.util.LinkedHashMap<>();
    detail.put("run", run);
    detail.put("task", repo.task(run.get("TASK_ID").toString()));
    detail.put("agents", repo.agents(id));
    detail.put("planNodes", plans.find(id));
    detail.put("tools", repo.tools(id));
    detail.put("models", repo.models(id));
    detail.put("handoffs", repo.handoffs(id));
    detail.put("events", includeEvents ? repo.events(id) : List.of());
    detail.put("checkpoints", repo.checkpoints(id));
    detail.put("runs", repo.runs(run.get("TASK_ID").toString()));
    detail.put("roleBudgets", repo.roleBudgets(run.get("TASK_ID").toString()));
    detail.put("toolBudgets", repo.toolBudgets(run.get("TASK_ID").toString()));
    return detail;
  }

  @PostMapping("/runs/{id}/cancel")
  Object cancel(@PathVariable String id) {
    service.cancel(id);
    return Map.of("accepted", true);
  }

  @GetMapping("/runs/{id}/skills")
  Object skills(@PathVariable String id) {
    repo.runSnapshot(id);
    return repo.skills(id);
  }

  @GetMapping("/runs/{id}/memory-load")
  Object memoryLoad(@PathVariable String id) {
    repo.runSnapshot(id);
    return memories.loadForRun(id);
  }

  @GetMapping("/runs/{id}/agent-sessions")
  Object agentSessions(@PathVariable String id) {
    return sessions.forRun(id);
  }

  @GetMapping("/runs/{id}/tool-grants")
  Object toolGrants(@PathVariable String id) {
    repo.runSnapshot(id);
    return repo.agentToolGrants(id);
  }

  @PostMapping("/runs/{id}/pause")
  Object pause(@PathVariable String id) {
    service.pause(id);
    return Map.of("accepted", true);
  }

  @PostMapping("/runs/{id}/resume")
  Object resume(@PathVariable String id) {
    service.resume(id);
    return Map.of("accepted", true);
  }

  @PostMapping("/runs/{id}/recover/{checkpoint}")
  Object recover(@PathVariable String id, @PathVariable String checkpoint) {
    return Map.of("runId", service.recover(id, checkpoint));
  }

  @GetMapping("/overview")
  Object overview() {
    return repo.overview();
  }

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
