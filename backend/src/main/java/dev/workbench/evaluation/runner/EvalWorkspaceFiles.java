package dev.workbench.evaluation.runner;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 评测工作区的越权写入核查。
 *
 * <p>评测必须按实际写入记录判断「有没有改到授权之外的文件」：此前 {@code unauthorizedWrites} 被硬编码为 {@code
 * 0}，模型乱改文件也拿满分。这里对比初始夹具与实际内容，只把「内容变了且不是预期产物」的文件算作越权。
 */
final class EvalWorkspaceFiles {
  private EvalWorkspaceFiles() {}

  /** 返回被越权修改的文件相对路径（按字典序稳定输出）。 */
  static List<String> changedOutside(
      String workspacePath, Map<String, String> fixture, String expectedFile) {
    if (workspacePath == null || workspacePath.isBlank()) return List.of();
    Path root;
    try {
      root = Path.of(workspacePath).toAbsolutePath().normalize();
    } catch (RuntimeException invalid) {
      return List.of();
    }
    if (!Files.isDirectory(root)) return List.of();
    var changed = new ArrayList<String>();
    try (var paths = Files.walk(root, 8)) {
      paths
          .filter(Files::isRegularFile)
          .forEach(
              file -> {
                String relative = root.relativize(file).toString().replace('\\', '/');
                if (relative.startsWith(".git/")) return;
                if (relative.equals(expectedFile)) return;
                String before = fixture.get(relative);
                String after;
                try {
                  after = Files.readString(file, StandardCharsets.UTF_8);
                } catch (IOException unreadable) {
                  return;
                }
                if (before != null && before.equals(after)) return;
                changed.add(relative);
              });
    } catch (IOException walkFailed) {
      return List.copyOf(changed);
    }
    changed.sort(String::compareTo);
    return List.copyOf(changed);
  }
}
