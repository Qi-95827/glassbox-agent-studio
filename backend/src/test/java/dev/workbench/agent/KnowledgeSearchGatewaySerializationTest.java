package dev.workbench.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.workbench.domain.EventType;
import dev.workbench.domain.ModelKind;
import dev.workbench.domain.ToolKind;
import dev.workbench.framework.agent.assembly.AgentAssemblySnapshot;
import dev.workbench.framework.agent.execution.AgentRuntime;
import dev.workbench.framework.tool.ToolEffect;
import dev.workbench.framework.tool.ToolFingerprint;
import dev.workbench.framework.tool.ToolGateway;
import dev.workbench.framework.trace.GlassboxModels;
import dev.workbench.memory.knowledge.KnowledgeRagService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * 知识检索经过真实 ToolGateway 序列化后，模型收到的文本只能含入选片段。
 *
 * <p>回归：工具直接返回整包 {@code RetrievalEvidence}，网关把它整包 JSON 序列化进 observation，
 * 于是「已裁剪」的候选正文仍然进了模型上下文。这里断言网关返回的那串 JSON 本身不含未入选正文。
 */
class KnowledgeSearchGatewaySerializationTest {
  private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
  private final KnowledgeRagService rag = mock(KnowledgeRagService.class);

  @Test
  void gatewaySerializedObservationNeverContainsDroppedPassageBodies() throws Exception {
    when(rag.retrieveWithEvidence("ws-1", "排序", 5)).thenReturn(evidence());
    var spec = KnowledgeSearchTool.specification(rag, "ws-1", "zh-CN");
    var gateway = new ToolGateway(json, new SilentRuntime(), new AtomicReference<>());
    Map<String, String> input = Map.of("query", "排序");

    String observation =
        gateway.execute(
            ToolKind.KNOWLEDGE_SEARCH,
            1,
            ToolFingerprint.of(ToolKind.KNOWLEDGE_SEARCH, "排序"),
            json.writeValueAsString(input),
            ToolEffect.READ_ONLY,
            spec.startMessage(),
            value -> spec.summarize().apply(input, value),
            () -> spec.validate().accept(input),
            null,
            null,
            null,
            List.of(),
            () -> spec.action().execute(input, () -> {}));

    var result = json.readTree(observation);
    assertEquals("SUCCEEDED", result.path("status").asText(), observation);
    assertTrue(
        result.path("data").path("passages").get(0).path("content").asText().contains("数组越界"),
        observation);
    assertFalse(observation.contains("大数据量下使用归并排序。"), "未入选正文不得进入模型 observation");
    assertFalse(observation.contains("BELOW_THRESHOLD"), observation);
    assertFalse(observation.contains("性能"), "未入选候选的标题也不该出现：" + observation);
  }

  private static KnowledgeRagService.RetrievalEvidence evidence() {
    return new KnowledgeRagService.RetrievalEvidence(
        "排序算法",
        true,
        List.of(
            new KnowledgeRagService.EvidenceItem(
                "chunk-1", "doc-1", "排序设计", "docs/sort.md", "边界处理",
                "数组越界需要先校验长度。", 42, 1.8, 0.61, 0.033, 0.44, true, ""),
            new KnowledgeRagService.EvidenceItem(
                "chunk-2", "doc-1", "排序设计", "docs/sort.md", "性能",
                "大数据量下使用归并排序。", 37, 0.02, 0.05, 0.001, 0.004, false, "BELOW_THRESHOLD")),
        1,
        42);
  }

  private static final class SilentRuntime implements AgentRuntime {
    @Override
    public void beforeModel(ModelKind modelName) {}

    @Override
    public void modelResult(Integer inputTokens, Integer outputTokens) {}

    @Override
    public void assemblyResolved(AgentAssemblySnapshot.Components components) {}

    @Override
    public UUID beforeTool(ToolKind tool, int version, String fingerprint) {
      return UUID.randomUUID();
    }

    @Override
    public void check() {}

    @Override
    public void event(EventType type, String message) {}

    @Override
    public void toolResult(String result) {}

    @Override
    public void toolRejected(String safeMessage) {}

    @Override
    public void toolTrace(UUID callId, GlassboxModels.ToolCallTrace trace) {}
  }
}
