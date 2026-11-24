package dev.workbench.infrastructure.mcp;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * STDIO MCP 传输的宿主机安全边界：环境变量、参数与工作目录都必须受平台控制。
 *
 * <p>这里的反例对应 S-01：普通用户通过 {@code PATH}/{@code NODE_OPTIONS}/{@code LD_PRELOAD} 或解释器
 * 行内执行开关即可在宿主机执行任意命令，从而读取密钥文件。
 */
class StdioMcpTransportSecurityTest {
  private final ExecutorService executor =
      Executors.newSingleThreadExecutor(
          runnable -> {
            Thread thread = new Thread(runnable, "stdio-mcp-test");
            thread.setDaemon(true);
            return thread;
          });

  @AfterEach
  void tearDown() {
    executor.shutdownNow();
  }

  /** 使用当前 JVM 的 java 可执行文件，避免测试依赖宿主 PATH。 */
  private static final String JAVA = javaExecutable();

  private static String javaExecutable() {
    boolean windows =
        System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
    return Path.of(System.getProperty("java.home"), "bin", windows ? "java.exe" : "java")
        .toString();
  }

  private StdioMcpTransport transport(List<String> args, Map<String, String> environment)
      throws Exception {
    return new StdioMcpTransport(
        JAVA, args, environment, List.of(JAVA), executor, Duration.ofSeconds(5));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "PATH",
        "NODE_OPTIONS",
        "LD_PRELOAD",
        "LD_LIBRARY_PATH",
        "PYTHONPATH",
        "PYTHONSTARTUP",
        "DOCKER_HOST",
        "JAVA_TOOL_OPTIONS",
        "_JAVA_OPTIONS",
        "GIT_SSH_COMMAND",
        "SYSTEMROOT",
        "TEMP"
      })
  void rejectsProtectedEnvironmentOverrides(String key) {
    var error =
        assertThrows(
            IllegalArgumentException.class,
            () -> transport(List.of("-version"), Map.of(key, "/tmp/evil")));
    assertTrue(error.getMessage().contains(key), error.getMessage());
  }

  @Test
  void allowsHarmlessCustomEnvironmentVariables() throws Exception {
    var transport = transport(List.of("-version"), Map.of("MCP_LOG_LEVEL", "debug"));
    transport.close();
  }

  @ParameterizedTest
  @ValueSource(strings = {"-e", "--eval", "-c", "--print", "-p", "--exec"})
  void rejectsInlineCodeExecutionFlags(String flag) {
    assertThrows(IllegalArgumentException.class, () -> transport(List.of(flag, "code"), Map.of()));
  }

  @Test
  void rejectsPathTraversalArguments() {
    assertThrows(
        IllegalArgumentException.class, () -> transport(List.of("../config/master.key"), Map.of()));
    assertThrows(
        IllegalArgumentException.class,
        () -> transport(List.of("..\\data\\workbench.mv.db"), Map.of()));
  }

  @Test
  void rejectsControlCharactersInArguments() {
    assertThrows(
        IllegalArgumentException.class, () -> transport(List.of("ok\ninjected"), Map.of()));
    assertThrows(IllegalArgumentException.class, () -> transport(List.of("ok\0bad"), Map.of()));
  }

  @Test
  void rejectsTooManyArguments() {
    var args = new ArrayList<String>();
    for (int index = 0; index < 40; index++) args.add("--flag" + index);
    assertThrows(IllegalArgumentException.class, () -> transport(args, Map.of()));
  }

  @Test
  void rejectsCommandOutsideWhitelist() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new StdioMcpTransport(
                "powershell",
                List.of("-version"),
                Map.of(),
                List.of(JAVA),
                executor,
                Duration.ofSeconds(5)));
  }

  @Test
  void runsInIsolatedWorkingDirectoryThatIsRemovedOnClose() throws Exception {
    var before = new ArrayList<>(temporaryMcpDirectories());
    var transport = transport(List.of("-version"), Map.of());
    var created = new ArrayList<>(temporaryMcpDirectories());
    created.removeAll(before);
    assertFalse(created.isEmpty(), "子进程必须运行在专用临时目录");
    transport.close();
    for (String directory : created) {
      assertFalse(
          Files.exists(Path.of(System.getProperty("java.io.tmpdir"), directory)),
          "关闭后应清理临时工作目录：" + directory);
    }
  }

  private static List<String> temporaryMcpDirectories() throws Exception {
    Path root = Path.of(System.getProperty("java.io.tmpdir"));
    try (Stream<Path> entries = Files.list(root)) {
      return entries
          .filter(Files::isDirectory)
          .map(path -> path.getFileName().toString())
          .filter(name -> name.startsWith("workbench-mcp-"))
          .toList();
    }
  }
}
