package dev.workbench.planning;

import dev.workbench.planning.PlanCandidateInput;
import dev.workbench.planning.PlanCandidateService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 运行创建前的候选计划入口；不创建任务、运行或任何工具授权。 */
@RestController
public class PlanCandidateController {
  private final PlanCandidateService candidates;

  public PlanCandidateController(PlanCandidateService candidates) {
    this.candidates = candidates;
  }

  public record GenerateRequest(
      String requestId, PlanCandidateInput input, String previousCandidateId, String draftKey) {}

  @PostMapping("/api/plan-candidates")
  PlanCandidateService.CandidateView generate(
      @RequestBody GenerateRequest request,
      @org.springframework.web.bind.annotation.RequestHeader(
              value = "Accept-Language",
              required = false)
          String acceptLanguage) {
    var input =
        request.input() == null || request.input().locale() != null
            ? request.input()
            : request.input().withLocale(acceptLanguage);
    return candidates.generate(
        new PlanCandidateService.GenerateRequest(
            request.requestId(), input, request.previousCandidateId(), request.draftKey()));
  }

  @GetMapping("/api/plan-candidates/{id}")
  PlanCandidateService.CandidateView get(@PathVariable String id) {
    return candidates.get(id);
  }

  @GetMapping("/api/plan-candidates")
  List<PlanCandidateService.CandidateView> list(@RequestParam String groupId) {
    return candidates.list(groupId);
  }

  /** 取消生成中的候选；晚到的模型响应不会改写状态。 */
  @PostMapping("/api/plan-candidates/{id}/cancel")
  PlanCandidateService.CandidateView cancel(@PathVariable String id) {
    return candidates.cancel(id);
  }

  public record ReviseRequest(String expectedCandidateSha256, PlanCandidateDraft draft) {}

  /** 用校验通过的新图覆盖就绪候选；校验失败不落库，已确认的候选只能继续用原运行。 */
  @PutMapping("/api/plan-candidates/{id}/draft")
  PlanCandidateService.Revised revise(
      @PathVariable String id, @RequestBody ReviseRequest request) {
    return candidates.revise(
        id, request.draft(), request.expectedCandidateSha256());
  }

  public record ConfirmRequest(
      String expectedCandidateSha256,
      String expectedInputSha256,
      int expectedInputVersion,
      String modelMode,
      Long maxCostMicros,
      String modelConnectionId) {}

  /** 确认候选并创建唯一运行；请求必须携带确认时看到的候选与输入摘要。 */
  @PostMapping("/api/plan-candidates/{id}/runs")
  PlanCandidateService.Confirmed confirm(
      @PathVariable String id, @RequestBody ConfirmRequest request) {
    return candidates.confirm(
        id,
        new PlanCandidateService.ConfirmRequest(
            request.expectedCandidateSha256(),
            request.expectedInputSha256(),
            request.expectedInputVersion(),
            request.modelMode(),
            request.maxCostMicros(),
            request.modelConnectionId()));
  }
}
