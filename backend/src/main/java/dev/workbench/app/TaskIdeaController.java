package dev.workbench.app;

import dev.workbench.task.TaskIdeaService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TaskIdeaController {
  private final TaskIdeaService ideas;

  public TaskIdeaController(TaskIdeaService ideas) {
    this.ideas = ideas;
  }

  public record IdeaRequest(String idea, TaskIdeaService.DraftMode mode) {}

  @PostMapping("/api/task-ideas/draft")
  TaskIdeaService.Draft draft(@RequestBody IdeaRequest request) {
    return ideas.draft(request.idea(), request.mode());
  }
}
