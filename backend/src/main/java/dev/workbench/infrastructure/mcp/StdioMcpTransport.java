package dev.workbench.infrastructure.mcp;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * 受控 STDIO 传输：命令白名单、最小环境变量、逐行 JSON-RPC 与超时熔断。
 *
 * <p>所有子进程由外部托管执行器读取响应，不创建裸线程。
 */
public final class StdioMcpTransport implements McpTransport {
  private final Process process;
  private final BufferedWriter input;
  private final BufferedReader output;
  private final ExecutorService executor;
  private final Duration timeout;

  public StdioMcpTransport(
      String command,
      List<String> args,
      java.util.Map<String, String> environment,
      List<String> allowedCommands,
      ExecutorService executor,
      Duration timeout)
      throws Exception {
    String executable = command == null ? "" : command.strip();
    if (allowedCommands == null || allowedCommands.stream().noneMatch(executable::equals))
      throw new IllegalArgumentException("MCP 命令不在白名单中：" + executable);
    var builder = new ProcessBuilder();
    var commandLine = new java.util.ArrayList<String>();
    commandLine.add(executable);
    commandLine.addAll(args == null ? List.of() : args);
    builder.command(commandLine);
    builder.environment().clear();
    builder.environment().put("PATH", System.getenv().getOrDefault("PATH", ""));
    builder.environment().put("SYSTEMROOT", System.getenv().getOrDefault("SYSTEMROOT", ""));
    builder.environment().put("TEMP", System.getenv().getOrDefault("TEMP", ""));
    if (environment != null) builder.environment().putAll(environment);
    builder.redirectErrorStream(false);
    this.process = builder.start();
    this.input = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
    this.output = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
    this.executor = executor;
    this.timeout = timeout;
  }

  @Override
  public String exchange(String request) throws Exception {
    Future<String> future =
        executor.submit(
            () -> {
              input.write(request);
              input.newLine();
              input.flush();
              String line;
              while ((line = output.readLine()) != null) {
                String trimmed = line.strip();
                if (trimmed.startsWith("{")) return trimmed;
              }
              throw new IllegalStateException("MCP 子进程已关闭，未返回响应。");
            });
    try {
      return future.get(Math.max(1, timeout.toMillis()), TimeUnit.MILLISECONDS);
    } catch (java.util.concurrent.TimeoutException e) {
      future.cancel(true);
      throw new IllegalStateException("MCP 子进程响应超时。", e);
    } finally {
      if (process != null && !process.isAlive()) process.destroyForcibly();
    }
  }

  @Override
  public void send(String request) throws Exception {
    input.write(request);
    input.newLine();
    input.flush();
  }

  @Override
  public void close() {
    try {
      input.close();
    } catch (Exception ignored) {
      // 子进程可能已退出。
    }
    if (process != null && process.isAlive()) process.destroyForcibly();
  }
}
