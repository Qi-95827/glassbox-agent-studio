package dev.workbench.planning;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.workbench.domain.ExecutionScope;
import dev.workbench.domain.ExecutionScopeResolver;
import dev.workbench.framework.planning.PlanCandidateModel;
import dev.workbench.framework.workflow.plan.PlanningRunReader;
import dev.workbench.domain.TaskEntryKind;
import dev.workbench.persistence.entity.PlanCandidateEntity;
import dev.workbench.persistence.repository.PlanCandidateRepository;
import dev.workbench.privateagent.PrivateAgentCatalog;
import dev.workbench.store.RunRecordService;
import dev.workbench.workspace.WorkspaceAuthorizationService;
import dev.workbench.workspace.WorkspaceService;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

/** 提交被拒绝时候选必须离开 GENERATING，且不留下队列引用。 */
class PlanCandidateQueueRejectionTest {
  @Test
  void rejectedSubmissionMarksCandidateFailedAndReleasesPending() {
    var store = mock(PlanCandidateRepository.class);
    var txManager = mock(PlatformTransactionManager.class);
    when(txManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
    var tx = new TransactionTemplate(txManager);
    var scopes = mock(ExecutionScopeResolver.class);
    when(scopes.current()).thenReturn(ExecutionScope.LOCAL);
    var workspaces = mock(WorkspaceService.class);
    when(workspaces.get("ws"))
        .thenReturn(
            new WorkspaceService.Workspace("ws", "repo", "path", "main", "abc", "branch", "now"));
    when(workspaces.read("ws", "a.java")).thenReturn(new WorkspaceService.FileContent("x", "sha"));
    when(store.findByOwnerScopeKeyAndRequestId(any(), any())).thenReturn(Optional.empty());
    when(store.latestInputVersion(any())).thenReturn(0);
    var saved = new AtomicReference<PlanCandidateEntity>();
    when(store.saveAndFlush(any()))
        .thenAnswer(
            invocation -> {
              saved.set(invocation.getArgument(0));
              return saved.get();
            });
    when(store.findById(any())).thenAnswer(invocation -> Optional.ofNullable(saved.get()));

    var rejected = Executors.newSingleThreadExecutor();
    rejected.shutdown();
    var service =
        new PlanCandidateService(
            store,
            mock(PlanCandidateValidator.class),
            mock(PlanCandidateModel.class),
            scopes,
            workspaces,
            mock(WorkspaceAuthorizationService.class),
            mock(CandidateRunPersister.class),
            mock(CandidateRunDispatcher.class),
            mock(PlanningRunReader.class),
            new ObjectMapper().findAndRegisterModules(),
            tx,
            mock(dev.workbench.ownership.OwnershipService.class),
            mock(PrivateAgentCatalog.class),
            mock(CandidateBudgetValidator.class),
            rejected);

    var input =
        new PlanCandidateInput(
            "goal",
            TaskEntryKind.CODE,
            "ws",
            true,
            List.of("a.java"),
            List.of(),
            List.of("compile passes"),
            "deliverable");
    assertThrows(
        IllegalStateException.class,
        () -> service.generate(new PlanCandidateService.GenerateRequest("request-1", input, null, null)));

    PlanCandidateEntity row = saved.get();
    assertNotNull(row);
    verify(store)
        .settle(
            eq(row.getId()),
            eq(PlanCandidateStatus.FAILED.name()),
            isNull(),
            isNull(),
            isNull(),
            contains("队列已满"),
            isNull(),
            isNull(),
            isNull(),
            anyString(),
            eq(PlanCandidateStatus.GENERATING.name()));
  }
}
