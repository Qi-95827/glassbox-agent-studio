package dev.workbench.agent;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.workbench.domain.ModelMode;
import dev.workbench.framework.agent.execution.AgentRuntime;
import dev.workbench.framework.context.CompactionRequest;
import dev.workbench.framework.context.CompactionResult;
import dev.workbench.framework.context.CompactionScope;
import dev.workbench.framework.context.ContextBreakdown;
import dev.workbench.framework.context.ContextCompactionHook;
import dev.workbench.framework.context.ContextMessage;
import dev.workbench.infrastructure.agentscope.ControlledModel;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.Model;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

/**
 * AgentScope 结构化块的完整展开与精准还原。
 *
 * <p>反例对应 `F-01`：只取 {@code getTextContent()} 会丢掉 tool_use 的参数与 tool_result 的输出，
 * 压缩既看不到工具语义、估算也严重偏低；用「角色 + 正文」队列复用 {@code Msg} 则会让同文消息串位。
 */
class ControlledModelBlockExtractionTest {
  private final Model delegate = mock(Model.class);
  private final AgentRuntime runtime = mock(AgentRuntime.class);

  private static Msg toolUseMessage(String id, String name, Map<String, Object> args) {
    return Msg.builder()
        .role(MsgRole.ASSISTANT)
        .content(
            List.of(
                TextBlock.builder().text("准备读取文件").build(),
                ToolUseBlock.builder().id(id).name(name).input(args).build()))
        .build();
  }

  private static Msg toolResultMessage(String id, String output) {
    return Msg.builder()
        .role(MsgRole.TOOL)
        .name("read_file")
        .content(ToolResultBlock.of(id, "read_file", TextBlock.builder().text(output).build()))
        .build();
  }

  private ControlledModel model(AtomicReference<CompactionRequest> captured) {
    when(delegate.getContextWindowSize()).thenReturn(100_000);
    when(delegate.stream(anyList(), anyList(), any()))
        .thenReturn(Flux.just(ChatResponse.builder().build()));
    ContextCompactionHook hook =
        request -> {
          captured.set(request);
          return new CompactionResult(
              request.messages(),
              new ContextBreakdown(
                  100_000,
                  1_000,
                  1_000,
                  Map.of("TOOL_OUTPUTS", 1_000),
                  dev.workbench.framework.context.CompactionTier.L0_OBSERVE_ONLY,
                  0,
                  0,
                  List.of(),
                  "",
                  List.of(),
                  "",
                  ""));
        };
    return new ControlledModel(
        delegate,
        ModelMode.USER,
        runtime,
        new AtomicReference<>(),
        error -> false,
        hook,
        CompactionScope.NONE,
        new AtomicReference<>());
  }

  @Test
  void toolUseArgumentsAndToolResultOutputAreExtracted() {
    var captured = new AtomicReference<CompactionRequest>();
    var messages =
        List.of(
            Msg.builder().role(MsgRole.SYSTEM).textContent("系统提示").build(),
            Msg.builder().role(MsgRole.USER).textContent("读取文件").build(),
            toolUseMessage("call-1", "read_file", Map.of("path", "src/App.jsx")),
            toolResultMessage("call-1", "文件内容：export default function App(){}"));

    model(captured).stream(messages, List.of(), null).collectList().block();

    var compacted = captured.get().messages();
    assertEquals(4, compacted.size());
    var use = compacted.get(2);
    assertTrue(use.content().contains("tool_use"), "必须保留 tool_use 名称与参数：" + use.content());
    assertTrue(use.content().contains("read_file"));
    assertTrue(use.content().contains("src/App.jsx"));
    assertEquals("call-1", use.toolCallId());
    assertEquals(2, use.originalIndex());
    var result = compacted.get(3);
    assertTrue(result.content().contains("文件内容"), "必须保留工具输出正文：" + result.content());
    assertEquals("call-1", result.toolCallId());
    assertEquals(3, result.originalIndex());
  }

  @Test
  void unchangedMessagesAreReusedByIndexNotByText() {
    var captured = new AtomicReference<CompactionRequest>();
    // 两条完全相同的 USER 正文：按下标还原才能保持顺序与对象身份。
    var first = Msg.builder().role(MsgRole.USER).textContent("重复指令").build();
    var second = Msg.builder().role(MsgRole.USER).textContent("重复指令").build();
    var messages = List.of(first, second);

    var controlled = model(captured);
    var seen = new ArrayList<Msg>();
    when(delegate.stream(anyList(), anyList(), any()))
        .thenAnswer(
            invocation -> {
              seen.addAll(invocation.getArgument(0));
              return Flux.just(ChatResponse.builder().build());
            });
    controlled.stream(messages, List.of(), null).collectList().block();

    assertEquals(2, seen.size());
    assertSame(first, seen.get(0), "第一条重复消息必须复用它自己的原始对象");
    assertSame(second, seen.get(1), "第二条重复消息必须复用它自己的原始对象");
  }

  @Test
  void rewrittenToolResultIsRebuiltWithTheSameCallId() {
    var captured = new AtomicReference<CompactionRequest>();
    when(delegate.getContextWindowSize()).thenReturn(100_000);
    ContextCompactionHook hook =
        request -> {
          captured.set(request);
          var updated = new ArrayList<ContextMessage>();
          for (var message : request.messages()) {
            if (message.toolResult()) {
              updated.add(
                  new ContextMessage(
                      message.role(), message.name(), "[已卸载]", message.toolCallId()));
              continue;
            }
            updated.add(message);
          }
          return new CompactionResult(
              updated,
              new ContextBreakdown(
                  100_000,
                  1_000,
                  500,
                  Map.of("TOOL_OUTPUTS", 500),
                  dev.workbench.framework.context.CompactionTier.L1_TOOL_OFFLOAD,
                  500,
                  50,
                  List.of("call-9"),
                  "",
                  List.of(),
                  "",
                  ""));
        };
    var controlled =
        new ControlledModel(
            delegate,
            ModelMode.USER,
            runtime,
            new AtomicReference<>(),
            error -> false,
            hook,
            CompactionScope.NONE,
            new AtomicReference<>());
    var messages =
        List.of(
            Msg.builder().role(MsgRole.USER).textContent("读取").build(),
            toolUseMessage("call-9", "read_file", Map.of("path", "a.txt")),
            toolResultMessage("call-9", "原始大段输出"));
    var seen = new ArrayList<Msg>();
    when(delegate.stream(anyList(), anyList(), any()))
        .thenAnswer(
            invocation -> {
              seen.addAll(invocation.getArgument(0));
              return Flux.just(ChatResponse.builder().build());
            });

    controlled.stream(messages, List.of(), null).collectList().block();

    var rebuilt = seen.get(2);
    assertEquals(MsgRole.TOOL, rebuilt.getRole());
    var block = rebuilt.getFirstContentBlock(ToolResultBlock.class);
    assertNotNull(block, "被改写的工具结果必须仍是 ToolResultBlock，否则调用与结果失配");
    assertEquals("call-9", block.getId());
    String output =
        block.getOutput() == null
            ? ""
            : block.getOutput().stream()
                .filter(TextBlock.class::isInstance)
                .map(TextBlock.class::cast)
                .map(TextBlock::getText)
                .collect(java.util.stream.Collectors.joining());
    assertTrue(output.contains("[已卸载]"), "改写的正文必须进入 ToolResultBlock：" + output);
  }
}
