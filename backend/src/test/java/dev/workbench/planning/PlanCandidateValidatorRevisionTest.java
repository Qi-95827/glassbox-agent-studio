package dev.workbench.planning;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.workbench.domain.AgentRole;
import dev.workbench.domain.TaskEntryKind;
import dev.workbench.domain.ToolKind;
import dev.workbench.framework.agent.definition.AgentDefinition;
import dev.workbench.framework.agent.definition.AgentDefinitionCatalog;
import dev.workbench.framework.agent.definition.AgentDefinitionRef;
import dev.workbench.framework.workflow.plan.RevisionEdge;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 反思回边校验：只允许审查节点指回直接上游，且每个审查节点最多一条。 */
class PlanCandidateValidatorRevisionTest {
  private PlanCandidateValidator validator;

  @BeforeEach
  void setUp() {
    var catalog = mock(AgentDefinitionCatalog.class);
    when(catalog.isPrivate(any())).thenReturn(false);
    when(catalog.fingerprint(any())).thenReturn("a".repeat(64));
    when(catalog.resolve(any()))
        .thenAnswer(
            invocation -> {
              AgentDefinitionRef ref = invocation.getArgument(0);
              return switch (ref.id()) {
                case "workspace-analysis" ->
                    new AgentDefinition(
                        ref, "分析", "分析", AgentRole.ANALYST, List.of(), List.of());
                case "workspace-edit" ->
                    new AgentDefinition(
                        ref,
                        "开发",
                        "开发",
                        AgentRole.CODER,
                        List.of(ToolKind.WORKSPACE_READ, ToolKind.WORKSPACE_WRITE),
                        List.of());
                case "workspace-change-review" ->
                    new AgentDefinition(
                        ref, "审查", "审查", AgentRole.REVIEWER, List.of(), List.of());
                default -> throw new IllegalArgumentException("unknown " + ref);
              };
            });
    validator = new PlanCandidateValidator(catalog);
  }

  @Test
  void acceptsReviewerEdgeBackToDirectUpstream() {
    var result = validator.validate(draft(List.of(new RevisionEdge("review", "implement", 2))), input());
    assertTrue(result.valid(), String.join("；", result.errors()));
    assertEquals(1, result.normalized().revisionEdges().size());
  }

  @Test
  void rejectsEdgeToNonUpstreamNode() {
    var result = validator.validate(draft(List.of(new RevisionEdge("review", "review", 2))), input());
    assertFalse(result.valid());
    assertTrue(result.errors().stream().anyMatch(error -> error.contains("直接上游")));
  }

  @Test
  void rejectsDuplicateReviewerEdges() {
    var result =
        validator.validate(
            draft(
                List.of(
                    new RevisionEdge("review", "implement", 1),
                    new RevisionEdge("review", "implement", 2))),
            input());
    assertFalse(result.valid());
    assertTrue(result.errors().stream().anyMatch(error -> error.contains("只能声明一条")));
  }

  private static PlanCandidateDraft draft(List<RevisionEdge> edges) {
    var implement =
        new PlanCandidateDraft.Node(
            "implement",
            "实现交付",
            PlanCandidateDraft.Role.IMPLEMENT,
            "实现 sort.js",
            List.of(),
            List.of("sort.js"),
            List.of("sort.js"),
            List.of(new PlanCandidateDraft.ExpectedOutput(PlanCandidateDraft.OutputKind.FILE, "sort.js", true)),
            List.of("写入授权文件"),
            null);
    var review =
        new PlanCandidateDraft.Node(
            "review",
            "交付审查",
            PlanCandidateDraft.Role.REVIEW,
            "审查实现",
            List.of("implement"),
            List.of(),
            List.of(),
            List.of(new PlanCandidateDraft.ExpectedOutput(PlanCandidateDraft.OutputKind.TEXT, null, true)),
            List.of("给出结论"),
            null);
    return new PlanCandidateDraft(1, List.of(implement, review), List.of(), List.of(), edges);
  }

  private static PlanCandidateInput input() {
    return new PlanCandidateInput(
        "实现排序函数",
        TaskEntryKind.CODE,
        "workspace-1",
        true,
        List.of("sort.js"),
        List.of(),
        List.of("写入授权文件"),
        null,
        "BUILTIN",
        null,
        null,
        List.of(),
        false);
  }
}
