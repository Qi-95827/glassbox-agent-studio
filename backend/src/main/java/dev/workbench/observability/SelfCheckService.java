package dev.workbench.observability;

import dev.workbench.persistence.repository.SystemCatalogRepository;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** 个人版环境自检；只返回状态和固定说明，不输出密钥或路径内容。 */
@Service
public class SelfCheckService {
  private final SystemCatalogRepository catalog;
  private final Path workspaceRoot;

  public SelfCheckService(
      SystemCatalogRepository catalog,
      @Value("${workbench.workspace-root:data/workspaces}") String workspaceRoot) {
    this.catalog = catalog;
    this.workspaceRoot = Path.of(workspaceRoot).toAbsolutePath().normalize();
  }

  public Result check() {
    List<Check> checks = new ArrayList<>();
    try {
      Integer one = catalog.ping();
      checks.add(
          new Check(
              "DATABASE",
              "OK",
              one != null && one == 1 ? "数据库可查询。" : "数据库返回异常。"));
    } catch (RuntimeException e) {
      checks.add(new Check("DATABASE", "FAIL", "数据库不可查询，请检查连接和数据目录。"));
    }
    try {
      Integer tables = catalog.countPublicTables();
      checks.add(
          new Check(
              "SCHEMA",
              tables != null && tables > 10 ? "OK" : "FAIL",
              "已迁移表数量：" + (tables == null ? "未知" : tables) + "。"));
    } catch (RuntimeException e) {
      checks.add(new Check("SCHEMA", "FAIL", "无法读取数据库结构，迁移可能未完成。"));
    }
    try {
      Files.createDirectories(workspaceRoot);
      Path probe = Files.createTempFile(workspaceRoot, ".self-check-", ".tmp");
      Files.writeString(probe, "ok", StandardCharsets.UTF_8);
      Files.deleteIfExists(probe);
      checks.add(new Check("WORKSPACE_ROOT", "OK", "工作区根目录可创建和写入。"));
    } catch (Exception e) {
      checks.add(new Check("WORKSPACE_ROOT", "FAIL", "工作区根目录不可写，请检查权限和配置。"));
    }
    checkDocker(checks);
    boolean deepseek = envPresent("DEEPSEEK_API_KEY") || keyFilePresent();
    checks.add(
        new Check(
            "DEEPSEEK_KEY",
            deepseek ? "OK" : "WARN",
            deepseek ? "已配置 DeepSeek 密钥（不显示内容）。" : "未配置 DeepSeek 密钥；只能使用模拟模型。"));
    String status =
        checks.stream().anyMatch(check -> "FAIL".equals(check.status()))
            ? "FAIL"
            : checks.stream().anyMatch(check -> "WARN".equals(check.status())) ? "WARN" : "OK";
    return new Result(status, checks);
  }

  private void checkDocker(List<Check> checks) {
    var version = command("docker", "version", "--format", "{{.Server.Version}}");
    if (version.exitCode() == 0 && !version.output().isBlank())
      checks.add(new Check("DOCKER", "OK", "Docker 服务可用。"));
    else checks.add(new Check("DOCKER", "WARN", "Docker 不可用；模拟 Bug 容器检查将无法执行。"));
    var image = command("docker", "image", "inspect", "python:3.12-alpine");
    if (image.exitCode() == 0) checks.add(new Check("TEST_IMAGE", "OK", "测试镜像存在。"));
    else checks.add(new Check("TEST_IMAGE", "WARN", "测试镜像不存在，请预先准备 python:3.12-alpine。"));
  }

  private static CommandResult command(String... args) {
    try {
      Process process = new ProcessBuilder(args).redirectErrorStream(true).start();
      var output =
          new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      if (!process.waitFor(8, TimeUnit.SECONDS)) {
        process.destroyForcibly();
        return new CommandResult(-1, "超时");
      }
      return new CommandResult(process.exitValue(), output);
    } catch (Exception e) {
      return new CommandResult(-1, "不可用");
    }
  }

  private static boolean envPresent(String name) {
    String value = System.getenv(name);
    return value != null && !value.isBlank();
  }

  private static boolean keyFilePresent() {
    try {
      Path key = Path.of("config", "deepseek-api-key.txt");
      return Files.isRegularFile(key) && Files.size(key) > 0;
    } catch (Exception e) {
      return false;
    }
  }

  private record CommandResult(int exitCode, String output) {}

  public record Check(String name, String status, String detail) {}

  public record Result(String status, List<Check> checks) {
    public Result {
      checks = List.copyOf(checks);
    }
  }
}
