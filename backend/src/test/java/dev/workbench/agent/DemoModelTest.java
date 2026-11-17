package dev.workbench.agent;

import static org.junit.jupiter.api.Assertions.*;

import dev.workbench.domain.AgentRole;
import dev.workbench.domain.Scenario;
import dev.workbench.infrastructure.model.DemoModel;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 模拟模型必须使用合法的工具参数，且不能把框架错误当作工具成功。 */
class DemoModelTest {
  @Test
  void validatesLatestToolResultInsteadOfAnyPreviousSuccess() {
    var model = new DemoModel(AgentRole.ANALYST, true, Scenario.NORMAL, List.of("a.txt", "b.txt"));
    var first = model.stream(List.of(), List.of(), null).blockFirst();
    assertNotNull(first);
    var call = (ToolUseBlock) first.getContent().getFirst();
    assertEquals("a.txt", call.getInput().get("path"));
    assertTrue(call.getContent().contains("a.txt"));
    Msg previousSuccess =
        Msg.builder()
            .name("tool")
            .role(MsgRole.TOOL)
            .content(ToolResultBlock.text("{\"status\":\"SUCCEEDED\"}"))
            .build();
    Msg latestError =
        Msg.builder()
            .name("tool")
            .role(MsgRole.TOOL)
            .content(ToolResultBlock.error("参数校验失败"))
            .build();
    assertThrows(
        RuntimeException.class,
        () ->
            model.stream(List.of(previousSuccess, latestError), List.of(), null)
                .collectList()
                .block());
  }
}
