package dev.workbench.infrastructure.model;

import dev.workbench.framework.outbound.OutboundHttpClients;
import io.agentscope.core.model.transport.HttpTransport;
import io.agentscope.core.model.transport.HttpTransportConfig;
import io.agentscope.core.model.transport.JdkHttpTransport;
import java.time.Duration;

/**
 * 把平台统一的出站客户端接入 AgentScope 模型传输层。
 *
 * <p>SDK 默认传输器使用 {@code Redirect.NORMAL} 自动跟随 302，会把已通过 {@link
 * dev.workbench.framework.outbound.OutboundUrlGuard} 校验的公网模型地址重定向到内网元数据端点； 这里显式注入共享
 * HttpClient，从传输层禁止重定向。
 */
final class ModelHttpTransports {
  private ModelHttpTransports() {}

  static HttpTransport hardened(Duration connectTimeout) {
    var config = HttpTransportConfig.builder().connectTimeout(connectTimeout).build();
    return new JdkHttpTransport(OutboundHttpClients.shared(), config);
  }
}
