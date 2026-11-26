package dev.workbench.planning;

import dev.workbench.ownership.OwnershipService;
import java.util.ArrayList;
import java.util.List;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 可视化编排画布的校验预览接口。
 *
 * <p>只做校验、不落库、不创建候选：画布每次拖动都可以调用它拿到结构化冲突，但真正的保存
 * 仍走既有的候选确认链路，因此不会因为「预览」产生任何可执行状态。
 *
 * <p>鉴权：请求体带工作区时先核对归属；不带工作区时也要求已登录会话（由全局鉴权过滤器保证）。
 * 请求可以附带与生成候选时相同的规划输入，带上后会额外运行真实的
 * {@link PlanCandidateValidator}，把业务规则错误一并返回。
 */
@RestController
@RequestMapping("/api/plans")
public class PlanDraftValidationController {
  private final PlanCandidateValidator validator;
  private final OwnershipService ownership;

  public PlanDraftValidationController(
      PlanCandidateValidator validator, OwnershipService ownership) {
    this.validator = validator;
    this.ownership = ownership;
  }

  /**
   * 校验请求。
   *
   * @param draft 待校验的候选图；为空时直接拒绝。
   * @param input 可选的规划输入；带上才运行完整业务校验。
   */
  public record Request(PlanCandidateDraft draft, PlanCandidateInput input) {}

  /**
   * 校验结果。
   *
   * @param valid 既没有结构化冲突、也没有业务规则错误时为 true。
   * @param errors 业务规则错误原文（来自 {@link PlanCandidateValidator}）；未提供输入时为空。
   * @param conflicts 图形层面的结构化冲突，供画布高亮。
   * @param conflictingNodeKeys 出现过冲突的节点键，便于前端一次性取到高亮集合。
   */
  public record Response(
      boolean valid,
      List<String> errors,
      List<PlanDraftConflictAnalyzer.Conflict> conflicts,
      List<String> conflictingNodeKeys) {}

  @PostMapping("/validate-draft")
  public Response validateDraft(
      @RequestBody Request request,
      @RequestHeader(value = "Accept-Language", required = false) String language) {
    if (request == null || request.draft() == null)
      throw new IllegalArgumentException("缺少待校验的候选计划。");
    var input = request.input();
    if (input != null && input.workspaceId() != null) ownership.requireWorkspace(input.workspaceId());

    var analysis = PlanDraftConflictAnalyzer.analyze(request.draft());
    var errors = new ArrayList<String>();
    if (input != null) {
      var result = validator.validate(request.draft(), input.withLocale(language), ownership.scope().ownerScopeKey());
      errors.addAll(result.errors());
    }
    return new Response(
        analysis.clean() && errors.isEmpty(),
        List.copyOf(errors),
        analysis.conflicts(),
        List.copyOf(analysis.byNodeKey().keySet()));
  }
}
