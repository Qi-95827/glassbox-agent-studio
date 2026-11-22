package dev.workbench.orchestration;

import dev.workbench.planning.EntryPolicyService;
import java.util.Map;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/** 工作台任务入口：任务列表、创建、归档、总览与入口策略。运行控制见 WorkbenchRunController。 */
@RestController
@RequestMapping("/api")
public class WorkbenchTaskController {
  private final WorkbenchTaskService taskService;
  private final RunService service;
  private final EntryPolicyService entryPolicies;
  private final dev.workbench.ownership.OwnershipService ownership;

  public WorkbenchTaskController(
      WorkbenchTaskService taskService,
      RunService service,
      EntryPolicyService entryPolicies,
      dev.workbench.ownership.OwnershipService ownership) {
    this.taskService = taskService;
    this.service = service;
    this.entryPolicies = entryPolicies;
    this.ownership = ownership;
  }

  @GetMapping("/tasks")
  Object tasks() {
    return taskService.tasks();
  }

  @GetMapping("/entry-policies")
  Object entryPolicies() {
    return entryPolicies.all();
  }

  public record TaskArchiveRequest(boolean archived) {}

  @PostMapping("/tasks/{taskId}/archive")
  Object archiveTask(@PathVariable String taskId, @RequestBody TaskArchiveRequest request) {
    taskService.archive(taskId, request.archived());
    return Map.of("archived", request.archived());
  }

  @PostMapping("/tasks")
  Object create(@RequestBody CreateTaskRequest request) {
    if (request.workspaceId() != null) ownership.requireWorkspace(request.workspaceId());
    return Map.of("runId", service.create(request));
  }

  @GetMapping("/overview")
  Object overview() {
    return taskService.overview();
  }

  @ExceptionHandler(EmptyResultDataAccessException.class)
  @ResponseStatus(HttpStatus.NOT_FOUND)
  Object notFound() {
    return Map.of(
        "message", dev.workbench.framework.i18n.LocalizedMessages.get("record.notFound"));
  }

  @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
  @ResponseStatus(HttpStatus.BAD_REQUEST)
  Object invalid(RuntimeException e) {
    return Map.of("message", e.getMessage());
  }
}
