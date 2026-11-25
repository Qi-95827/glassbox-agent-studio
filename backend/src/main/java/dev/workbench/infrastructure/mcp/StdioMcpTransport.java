package dev.workbench.infrastructure.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.workbench.framework.i18n.LocalizedMessages;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * 受控 STDIO 传输：命令白名单、最小环境变量、参数校验、独立工作目录与超时熔断。
 *
 * <p>MCP 子进程与后端同权限运行，因此这里是宿主机安全的最后一道闸门：
 *
 * <ul>
 *   <li>环境变量：只保留运行所必需的 {@code PATH}/{@code SYSTEMROOT}/{@code TEMP}，用户自定义变量 一律拒绝覆盖这些键，以及 {@code
 *       LD_*}/{@code DYLD_*}/{@code NODE_*}/{@code PYTHON*}/ {@code JAVA_*}/{@code DOCKER_*}/{@code
 *       GIT_*} 等能改变动态库搜索或运行时加载行为的变量；
 *   <li>参数：限制数量与长度，拒绝 {@code -e}/{@code -c}/{@code --eval}/{@code --print} 一类行内 代码执行开关与 {@code ..}
 *       目录穿越写法；
 *   <li>工作目录：固定到一次性临时空目录，避免子进程以应用根目录为基准读取 {@code config/} 与 {@code data/} 下的密钥与数据库文件。
 * </ul>
 *
 * <p>所有子进程由外部托管执行器读取响应，不创建裸线程。
 */
public final class StdioMcpTransport implements McpTransport {
  /** 可改变运行时加载行为、必须由平台独占控制的环境变量。 */
  private static final Set<String> PROTECTED_ENV_KEYS =
      Set.of(
          "PATH",
          "SYSTEMROOT",
          "WINDIR",
          "TEMP",
          "TMP",
          "HOME",
          "USERPROFILE",
          "LD_PRELOAD",
          "LD_LIBRARY_PATH",
          "DYLD_INSERT_LIBRARIES",
          "DYLD_LIBRARY_PATH",
          "NODE_OPTIONS",
          "NODE_PATH",
          "PYTHONSTARTUP",
          "PYTHONPATH",
          "PYTHONHOME",
          "DOCKER_HOST",
          "DOCKER_CONFIG",
          "JAVA_TOOL_OPTIONS",
          "_JAVA_OPTIONS",
          "JDK_JAVA_OPTIONS",
          "CLASSPATH",
          "BASH_ENV",
          "ENV",
          "SHELL",
          "COMSPEC",
          "PATHEXT",
          "PERL5OPT",
          "RUBYOPT");

  /** 按前缀整体拒绝，覆盖 {@code LD_*}、{@code PYTHON*}、{@code NODE_*}、{@code GIT_SSH_COMMAND} 等家族。 */
  private static final List<String> PROTECTED_ENV_PREFIXES =
      List.of("LD_", "DYLD_", "PYTHON", "NODE_", "NPM_", "DOCKER_", "JAVA_", "_JAVA", "GIT_");

  /** 解释器行内代码执行开关；STDIO 传输只服务固定 MCP 服务包，不需要这些写法。 */
  private static final Set<String> INLINE_CODE_FLAGS =
      Set.of("-e", "--eval", "-p", "--print", "-c", "--command", "--exec", "--execute");

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final int MAX_ARGS = 32;
  private static final int MAX_ARG_LENGTH = 1024;

  private final Process process;
  private final BufferedWriter input;
  private final BufferedReader output;
  private final ExecutorService executor;
  private final Duration timeout;
  private final Path workingDirectory;

  public StdioMcpTransport(
      String command,
      List<String> args,
      Map<String, String> environment,
      List<String> allowedCommands,
      ExecutorService executor,
      Duration timeout)
      throws Exception {
    String executable = command == null ? "" : command.strip();
    if (allowedCommands == null || allowedCommands.stream().noneMatch(executable::equals))
      throw new IllegalArgumentException("MCP 命令不在白名单中：" + executable);
    List<String> commandArgs = args == null ? List.of() : List.copyOf(args);
    validateArgs(commandArgs);
    Map<String, String> extraEnvironment = environment == null ? Map.of() : environment;
    validateEnvironment(extraEnvironment);
    var builder = new ProcessBuilder();
    var commandLine = new java.util.ArrayList<String>();
    commandLine.add(executable);
    commandLine.addAll(commandArgs);
    builder.command(commandLine);
    this.workingDirectory = Files.createTempDirectory("workbench-mcp-");
    builder.directory(workingDirectory.toFile());
    builder.environment().clear();
    builder.environment().put("PATH", System.getenv().getOrDefault("PATH", ""));
    builder.environment().put("SYSTEMROOT", System.getenv().getOrDefault("SYSTEMROOT", ""));
    builder.environment().put("TEMP", System.getenv().getOrDefault("TEMP", ""));
    builder.environment().putAll(extraEnvironment);
    // 丢弃子进程 stderr，既不污染 stdout JSON-RPC 流，也防止 OS 管道缓冲区写满导致子进程死锁。
    builder.redirectError(ProcessBuilder.Redirect.DISCARD);
    this.process = builder.start();
    this.input =
        new BufferedWriter(
            new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
    this.output =
        new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
    this.executor = executor;
    this.timeout = timeout;
  }

  /** 参数必须是无换行、非行内执行的短字符串，避免借解释器开关执行任意代码。 */
  private static void validateArgs(List<String> args) {
    if (args.isEmpty()) return;
    if (args.size() > MAX_ARGS)
      throw new IllegalArgumentException(LocalizedMessages.get("outbound.mcp.args"));
    for (String arg : args) {
      if (arg == null || arg.length() > MAX_ARG_LENGTH)
        throw new IllegalArgumentException(LocalizedMessages.get("outbound.mcp.args"));
      String lower = arg.strip().toLowerCase(Locale.ROOT);
      if (INLINE_CODE_FLAGS.contains(lower)
          || lower.contains("../")
          || lower.contains("..\\")
          || "..".equals(lower))
        throw new IllegalArgumentException(LocalizedMessages.get("outbound.mcp.args"));
      if (arg.indexOf('\n') >= 0 || arg.indexOf('\r') >= 0 || arg.indexOf('\0') >= 0)
        throw new IllegalArgumentException(LocalizedMessages.get("outbound.mcp.args"));
    }
  }

  /** 用户自定义环境变量不得覆盖平台保留键，也不得携带换行/等号等可改变解析含义的字符。 */
  private static void validateEnvironment(Map<String, String> environment) {
    for (var entry : environment.entrySet()) {
      String key = entry.getKey() == null ? "" : entry.getKey().strip();
      if (key.isEmpty() || key.indexOf('=') >= 0 || key.indexOf('\0') >= 0)
        throw new IllegalArgumentException(environmentMessage(key));
      String upper = key.toUpperCase(Locale.ROOT);
      boolean protectedKey =
          PROTECTED_ENV_KEYS.contains(upper)
              || PROTECTED_ENV_PREFIXES.stream().anyMatch(upper::startsWith);
      if (protectedKey) throw new IllegalArgumentException(environmentMessage(key));
      String value = entry.getValue() == null ? "" : entry.getValue();
      if (value.indexOf('\0') >= 0 || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0)
        throw new IllegalArgumentException(environmentMessage(key));
    }
  }

  /**
   * 拒绝文案必须带上被拒绝的变量名，便于管理员判断是哪一项配置触发拦截。
   *
   * <p>文案缺失时回退到中文模板，避免把裸 key 抛给前端。
   */
  private static String environmentMessage(String key) {
    String template = LocalizedMessages.getOrDefault("outbound.mcp.env", "MCP 子进程不允许覆盖敏感环境变量：{0}");
    return java.text.MessageFormat.format(template, key);
  }

  @Override
  public String exchange(String request) throws Exception {
    // 同一子进程的写入与读取必须串行：并发交换会相互吞掉对方的响应行，造成串帧。
    synchronized (this) {
      if (process != null && !process.isAlive())
        throw new IllegalStateException("MCP 子进程已退出，需要重新连接服务器。");
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
        String response = future.get(Math.max(1, timeout.toMillis()), TimeUnit.MILLISECONDS);
        if (!sameRequestId(request, response)) {
          // 响应标识不匹配说明管道已经错位，继续复用只会把 A 的结果返回给 B。
          destroyIfAlive(process);
          throw new IllegalStateException("MCP 子进程返回了与请求不匹配的响应标识，已终止该子进程以避免串帧。");
        }
        return response;
      } catch (java.util.concurrent.TimeoutException e) {
        future.cancel(true);
        // 超时的子进程通常仍然存活并卡在读取上：必须强杀，否则会长期占用执行线程。
        destroyIfAlive(process);
        throw new IllegalStateException("MCP 子进程响应超时。", e);
      } catch (java.util.concurrent.ExecutionException e) {
        destroyIfAlive(process);
        throw new IllegalStateException("MCP 子进程交换失败。", e.getCause() == null ? e : e.getCause());
      } catch (InterruptedException e) {
        future.cancel(true);
        Thread.currentThread().interrupt();
        destroyIfAlive(process);
        throw e;
      }
    }
  }

  /**
   * 失败时是否需要强杀子进程。
   *
   * <p>只有**仍然存活**的僵死进程才需要销毁；原实现条件写反（{@code !process.isAlive()}），超时后从不清理， 两次超时就会永久占死 MCP 执行线程。
   */
  static boolean shouldDestroyForcibly(Process process) {
    return process != null && process.isAlive();
  }

  private static void destroyIfAlive(Process process) {
    if (shouldDestroyForcibly(process)) process.destroyForcibly();
  }

  /**
   * 校验 JSON-RPC 响应 {@code id} 与请求一致。
   *
   * <p>通知类请求没有 {@code id}，不做限制；有 id 的请求必须收到同一 id 的响应，否则视为串帧。
   */
  static boolean sameRequestId(String request, String response) {
    try {
      var requestId = JSON.readTree(request).path("id");
      if (requestId.isMissingNode() || requestId.isNull()) return true;
      var responseId = JSON.readTree(response).path("id");
      if (responseId.isMissingNode() || responseId.isNull()) return false;
      if (requestId.isNumber() && responseId.isNumber())
        return requestId.asLong() == responseId.asLong();
      return requestId.asText().equals(responseId.asText());
    } catch (Exception e) {
      return false;
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
    deleteWorkingDirectory();
  }

  /** 删除一次性工作目录；失败只留下空临时目录，不影响应用退出。 */
  private void deleteWorkingDirectory() {
    if (workingDirectory == null) return;
    try (var paths = Files.walk(workingDirectory)) {
      paths.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
    } catch (Exception ignored) {
      // 目录可能已被清理或仍被占用。
    }
  }
}
