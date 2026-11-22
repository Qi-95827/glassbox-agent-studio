package dev.workbench.infrastructure.mcp;

/** JSON-RPC 2.0 传输；一次 exchange 发送一个请求并返回一个响应。 */
public interface McpTransport extends AutoCloseable {
  String exchange(String request) throws Exception;

  /** 发送无 id 的通知；默认实现复用 exchange，STDIO 应覆盖为不等待响应。 */
  default void send(String request) throws Exception {
    exchange(request);
  }

  @Override
  default void close() {}
}
