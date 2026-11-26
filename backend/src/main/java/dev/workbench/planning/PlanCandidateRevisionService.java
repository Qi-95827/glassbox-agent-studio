package dev.workbench.planning;

import dev.workbench.domain.ExecutionScope;
import dev.workbench.domain.ExecutionScopeResolver;
import dev.workbench.persistence.entity.PlanCandidateEntity;
import java.util.List;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 候选图的人工修订入口：只接受校验通过的新图，并同步改写摘要与 Agent 绑定。
 *
 * <p>修订不新建候选、不改输入版本、不动工作区租约，也不绕过既有的确认规则：它只重写仍处于 READY 且尚未绑定 运行的候选内容，让「画布调整 → 校验 → 确认执行」成为同一条链路。校验失败时不落库并原样返回原因， 已确认或已过期的候选不会被改写，确认时仍会复核新摘要。
 */
class PlanCandidateRevisionService {
  private final PlanCandidateStoreService store;
  private final PlanCandidateValidator validator;
  private final ExecutionScopeResolver scopes;
  private final TransactionTemplate tx;

  PlanCandidateRevisionService(
      PlanCandidateStoreService store,
      PlanCandidateValidator validator,
      ExecutionScopeResolver scopes,
      TransactionTemplate tx) {
    this.store = store;
    this.validator = validator;
    this.scopes = scopes;
    this.tx = tx;
  }

  PlanCandidateService.Revised revise(
      String id, PlanCandidateDraft draft, String expectedCandidateSha256) {
    ExecutionScope scope = scopes.current();
    PlanCandidateEntity row = store.requireById(id, scope.ownerScopeKey());
    requireEditable(row, expectedCandidateSha256);
    PlanCandidateInput input = store.read(row.getInputJson(), PlanCandidateInput.class);
    var result =
        validator.validate(
            draft, input, scope.ownerScopeKey(), store.readPlanning(row.getAgentAliasesJson()));
    // 校验不通过就停在原候选：拒绝落库比写入一份「看起来改了但跑不起来」的图更安全。
    if (!result.valid()) {
      return new PlanCandidateService.Revised(false, result.errors(), store.view(row));
    }
    int changed =
        store.reviseDraft(
            row.getId(),
            scope.ownerScopeKey(),
            store.write(result.normalized()),
            result.candidateSha256(),
            store.write(PlanCandidateBindings.of(result.nodes())));
    if (changed != 1) throw new IllegalStateException("候选已被确认或过期，请刷新后重新修改。");
    return new PlanCandidateService.Revised(
        true, List.of(), store.view(store.reload(row)));
  }

  /** 只有就绪、未消费、且仍是本组最新输入版本的候选才能被改写。 */
  private void requireEditable(PlanCandidateEntity row, String expectedCandidateSha256) {
    if (row.getRunId() != null)
      throw new IllegalStateException("该候选已用于运行 " + row.getRunId() + "，不能再修改。");
    if (!PlanCandidateStatus.READY.name().equals(row.getStatus()))
      throw new IllegalStateException("候选当前状态为 " + row.getStatus() + "，不能修改。");
    if (store.latestInputVersion(row.getGroupId()) != row.getInputVersion())
      throw new IllegalStateException("已有更新的输入版本，请重新生成计划后再修改。");
    if (expectedCandidateSha256 != null
        && !expectedCandidateSha256.isBlank()
        && !expectedCandidateSha256.equals(row.getCandidateSha256()))
      throw new IllegalStateException("候选已被修改过，请刷新后再应用画布。");
  }
}
