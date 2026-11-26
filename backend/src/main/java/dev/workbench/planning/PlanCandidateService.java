package dev.workbench.planning;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.workbench.domain.ExecutionScopeResolver;
import dev.workbench.framework.workflow.plan.PlanningRunReader;
import dev.workbench.persistence.repository.PlanCandidateRepository;
import dev.workbench.workspace.WorkspaceAuthorizationService;
import dev.workbench.workspace.WorkspaceService;
import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.concurrent.ExecutorService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 运行创建前的候选计划兼容门面。
 *
 * <p>公开方法与嵌套请求/视图类型保持不变；生成状态机、工作区租约和确认物化分别委派给生成、工作区与确认服务。候选只做建议：服务端校验通过才进入 READY，任何状态都不会创建任务、运行、节点、工作区或工具授权。
 */
@Service
public class PlanCandidateService {
  private final PlanCandidateStoreService candidates;
  private final PlanCandidateGenerationService generation;
  private final PlanCandidateConfirmationService confirmation;
  private final PlanCandidateRevisionService revision;
  private final ExecutionScopeResolver scopes;

  @Autowired
  public PlanCandidateService(
      PlanCandidateRepository store,
      PlanCandidateValidator validator,
      dev.workbench.framework.planning.PlanCandidateModel model,
      ExecutionScopeResolver scopes,
      WorkspaceService workspaces,
      WorkspaceAuthorizationService workspaceAuthorization,
      CandidateRunPersister taskCreations,
      CandidateRunDispatcher runs,
      PlanningRunReader records,
      ObjectMapper json,
      TransactionTemplate tx,
      dev.workbench.ownership.OwnershipService ownership,
      dev.workbench.privateagent.PrivateAgentCatalog privateAgents,
      dev.workbench.modelconnection.ModelConnectionService modelConnections,
      CandidateBudgetValidator budgetValidator,
      @org.springframework.beans.factory.annotation.Qualifier("planningExecutor")
          ExecutorService executor) {
    this.scopes = scopes;
    this.candidates = new PlanCandidateStoreService(store, tx, json);
    var workspaceCandidates =
        new PlanCandidateWorkspaceService(candidates, workspaces, workspaceAuthorization);
    this.generation =
        new PlanCandidateGenerationService(
            candidates,
            workspaceCandidates,
            validator,
            model,
            scopes,
            ownership,
            privateAgents,
            modelConnections,
            json,
            tx,
            executor);
    this.confirmation =
        new PlanCandidateConfirmationService(
            candidates,
            workspaceCandidates,
            validator,
            scopes,
            taskCreations,
            runs,
            records,
            modelConnections,
            budgetValidator,
            tx);
    this.revision = new PlanCandidateRevisionService(candidates, validator, scopes, tx);
  }

  /** 兼容不使用 BYOK 的旧单元测试构造；生产装配必须显式传入连接服务。 */
  public PlanCandidateService(
      PlanCandidateRepository store,
      PlanCandidateValidator validator,
      dev.workbench.framework.planning.PlanCandidateModel model,
      ExecutionScopeResolver scopes,
      WorkspaceService workspaces,
      WorkspaceAuthorizationService workspaceAuthorization,
      CandidateRunPersister taskCreations,
      CandidateRunDispatcher runs,
      PlanningRunReader records,
      ObjectMapper json,
      TransactionTemplate tx,
      dev.workbench.ownership.OwnershipService ownership,
      dev.workbench.privateagent.PrivateAgentCatalog privateAgents,
      CandidateBudgetValidator budgetValidator,
      @org.springframework.beans.factory.annotation.Qualifier("planningExecutor")
          ExecutorService executor) {
    this(
        store,
        validator,
        model,
        scopes,
        workspaces,
        workspaceAuthorization,
        taskCreations,
        runs,
        records,
        json,
        tx,
        ownership,
        privateAgents,
        null,
        budgetValidator,
        executor);
  }

  public record GenerateRequest(
      String requestId, PlanCandidateInput input, String previousCandidateId, String draftKey) {}

  public record CandidateView(
      String id,
      String status,
      int schemaVersion,
      int inputVersion,
      String inputSha256,
      String candidateSha256,
      PlanCandidateInput input,
      PlanCandidateDraft candidate,
      java.util.Map<String, Object> agentBindings,
      List<String> errors,
      String modelName,
      Integer inputTokens,
      Integer outputTokens,
      String createdAt,
      String settledAt,
      String previousCandidateId,
      String groupId,
      String runId) {}

  /**
   * 保存一次生成请求并异步调用模型。
   *
   * <p>同一归属下相同请求标识与相同输入返回原候选；内容不同则冲突。声明了前一候选时沿用其组，让同组旧候选在提交新输入后过期。
   */
  public CandidateView generate(GenerateRequest request) {
    return generation.generate(request);
  }

  public CandidateView get(String id) {
    return candidates.view(candidates.requireById(id, scopes.current().ownerScopeKey()));
  }

  public List<CandidateView> list(String groupId) {
    return candidates.list(scopes.current().ownerScopeKey(), groupId).stream()
        .map(candidates::view)
        .toList();
  }

  /** 确认请求：客户端必须回传它看到的候选摘要、输入摘要和版本。 */
  public record ConfirmRequest(
      String expectedCandidateSha256,
      String expectedInputSha256,
      int expectedInputVersion,
      String modelMode,
      Long maxCostMicros,
      String modelConnectionId) {
    /** 兼容未使用自带 Key 的旧调用。 */
    public ConfirmRequest(
        String expectedCandidateSha256,
        String expectedInputSha256,
        int expectedInputVersion,
        String modelMode,
        Long maxCostMicros) {
      this(
          expectedCandidateSha256,
          expectedInputSha256,
          expectedInputVersion,
          modelMode,
          maxCostMicros,
          null);
    }
  }

  public record Confirmed(String runId, String taskId, String workspaceId) {}

  /**
   * 人工修订结果。
   *
   * <p>{@code applied=false} 表示结构校验未通过：候选保持原样，{@code errors} 是可直接展示的原因； 只有 {@code applied=true} 时 {@code candidate} 才是改写后的候选。
   */
  public record Revised(boolean applied, List<String> errors, CandidateView candidate) {
    public Revised {
      errors = errors == null ? List.of() : List.copyOf(errors);
    }
  }

  /**
   * 用校验通过的新图覆盖就绪候选。
   *
   * <p>这是「画布调整 → 确认执行」之间唯一的落库入口：不改输入版本与工作区基线，不新建候选， 校验失败即拒绝落库。确认时仍按保存的新摘要复核一次。
   */
  public Revised revise(String id, PlanCandidateDraft draft, String expectedCandidateSha256) {
    return revision.revise(id, draft, expectedCandidateSha256);
  }

  /**
   * 把 READY 候选物化为唯一的运行。
   *
   * <p>确认前复核候选摘要、输入摘要、输入版本、当前分组最新版本、工作区基线与文件版本；创建事务内再锁住候选行复核一次，重复确认返回已有运行而不是再派发。
   */
  public Confirmed confirm(String id, ConfirmRequest request) {
    return confirmation.confirm(id, request);
  }

  /** 取消只对生成中的候选生效；已结算候选保持原状态。 */
  @Transactional
  public CandidateView cancel(String id) {
    return generation.cancel(id);
  }

  /** 上一个进程遗留的生成中候选在启动时转为中断，不伪造成功。 */
  @EventListener(ApplicationReadyEvent.class)
  @Transactional
  public void interruptOrphans() {
    generation.interruptOrphans();
  }

  @PreDestroy
  void shutdown() {
    generation.shutdown();
  }
}
