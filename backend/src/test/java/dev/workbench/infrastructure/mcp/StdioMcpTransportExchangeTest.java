package dev.workbench.infrastructure.mcp;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;

/**
 * MCP STDIO 交换的串帧与僵死进程处理。
 *
 * <p>反例对应 `F-08`：超时后从不清理仍然存活的子进程，两次超时就能占死 MCP 执行线程；并发交换会相互 吞掉响应行；响应 id 不校验时会把 A 的结果返回给 B。
 */
class StdioMcpTransportExchangeTest {
  @Test
  void aliveProcessMustBeDestroyedOnFailure() {
    var alive = mock(Process.class);
    when(alive.isAlive()).thenReturn(true);
    var dead = mock(Process.class);
    when(dead.isAlive()).thenReturn(false);

    assertTrue(StdioMcpTransport.shouldDestroyForcibly(alive), "仍然存活的僵死子进程必须被强杀（旧实现条件写反）");
    assertFalse(StdioMcpTransport.shouldDestroyForcibly(dead), "已退出的进程无需再杀");
    assertFalse(StdioMcpTransport.shouldDestroyForcibly(null));
  }

  @Test
  void responseIdMustMatchRequestId() {
    assertTrue(
        StdioMcpTransport.sameRequestId(
            "{\"id\":1,\"method\":\"tools/list\"}", "{\"id\":1,\"result\":{}}"));
    assertTrue(
        StdioMcpTransport.sameRequestId("{\"id\":\"abc\"}", "{\"id\":\"abc\",\"result\":{}}"));
    assertFalse(
        StdioMcpTransport.sameRequestId(
            "{\"id\":1,\"method\":\"tools/list\"}", "{\"id\":2,\"result\":{}}"),
        "响应 id 与请求不一致即为串帧");
    assertFalse(
        StdioMcpTransport.sameRequestId("{\"id\":1}", "{\"result\":{}}"), "缺少 id 的响应不能当作本次请求的结果");
  }

  @Test
  void notificationsWithoutIdAreNotConstrained() {
    assertTrue(
        StdioMcpTransport.sameRequestId(
            "{\"method\":\"notifications/initialized\"}", "{\"result\":{}}"));
  }

  @Test
  void malformedJsonIsNeverAcceptedAsMatching() {
    assertFalse(StdioMcpTransport.sameRequestId("{\"id\":1}", "not-json"));
  }
}
