package dev.workbench.framework.outbound;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * 平台统一的出站 HTTP 客户端。
 *
 * <p>所有离开进程的请求（BYOK 模型探活、Embedding、MCP HTTP、评测裁判等）必须复用同一个客户端实例：
 *
 * <ul>
 *   <li>进程内复用可以避免每次请求新建 {@link HttpClient} 造成的 SelectorManager 线程与套接字句柄泄漏；
 *   <li>强制 {@link HttpClient.Redirect#NEVER}：跟随 302 会把已校验的公网地址重定向到内网或元数据端点， 直接击穿 {@link
 *       OutboundUrlGuard} 的判定；重定向一律视为请求失败，由调用方按不可用处理。
 * </ul>
 */
public final class OutboundHttpClients {
  /** 连接超时；读取超时仍由各调用方在单个请求上按业务设置。 */
  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

  private OutboundHttpClients() {}

  /**
   * 共享客户端。惰性创建一次，不随请求增长；JDK HttpClient 自身线程为守护线程，无需显式关闭。
   *
   * @return 禁止自动重定向的共享客户端
   */
  public static HttpClient shared() {
    return Holder.CLIENT;
  }

  private static final class Holder {
    private static final HttpClient CLIENT =
        HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
  }
}
