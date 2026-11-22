package dev.workbench.planning;

import dev.workbench.planning.TaskIdeaService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TaskIdeaController {
  private final TaskIdeaService ideas;

  public TaskIdeaController(TaskIdeaService ideas) {
    this.ideas = ideas;
  }

  public record IdeaRequest(
      String idea, TaskIdeaService.DraftMode mode, String modelConnectionId) {}

  @PostMapping("/api/task-ideas/draft")
  TaskIdeaService.Draft draft(
      @RequestBody IdeaRequest request,
      @org.springframework.web.bind.annotation.RequestHeader(
              value = "Accept-Language",
              required = false)
          String acceptLanguage) {
    return ideas.draft(
        request.idea(), request.mode(), request.modelConnectionId(), acceptLanguage);
  }
}
