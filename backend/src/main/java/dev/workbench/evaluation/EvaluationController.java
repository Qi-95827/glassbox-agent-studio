package dev.workbench.evaluation;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 本地评测入口；批次完成和案例通过分别展示。 */
@RestController
@RequestMapping("/api/evaluations")
public class EvaluationController {
  private final EvaluationService service;
  private final EvaluationRepository repository;
  private final EvaluationComparisonService comparisons;
  private final BugEvidenceAssessmentService bugAssessments;

  public record Detail(EvaluationBatch batch, List<EvaluationCase> cases) {}

  public record Created(String id) {}

  public record Accepted(boolean accepted) {}

  public EvaluationController(
      EvaluationService service,
      EvaluationRepository repository,
      EvaluationComparisonService comparisons,
      BugEvidenceAssessmentService bugAssessments) {
    this.service = service;
    this.repository = repository;
    this.comparisons = comparisons;
    this.bugAssessments = bugAssessments;
  }

  @PostMapping
  public Created start() {
    return new Created(service.start());
  }

  @PostMapping("/{id}/cancel")
  public Accepted cancel(@PathVariable String id) {
    service.cancel(id);
    return new Accepted(true);
  }

  @GetMapping
  public List<EvaluationBatch> list() {
    return repository.list();
  }

  @GetMapping("/{id}")
  public Detail detail(@PathVariable String id) {
    return new Detail(repository.get(id), repository.cases(id));
  }

  /** 只评估已有记录，不会启动测试或修改工作区。 */
  @GetMapping("/bug-evidence")
  public BugEvidenceAssessment assessBugEvidence(@RequestParam String runId) {
    return bugAssessments.assess(runId);
  }

  @GetMapping("/compare")
  public EvaluationComparison compare(
      @RequestParam String baseline, @RequestParam String candidate) {
    return comparisons.compare(baseline, candidate);
  }
}
