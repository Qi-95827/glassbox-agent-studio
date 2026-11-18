package dev.workbench.workspace;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 只在服务端绑定的授权文件集合中执行有界的字面文本搜索。 */
public final class AuthorizedCodeSearch {
  private static final int MAX_QUERY_CODE_POINTS = 100;
  private static final int MAX_MATCHES = 50;
  private static final int MAX_EXCERPT_CODE_POINTS = 240;
  private final WorkspaceService workspaces;

  public AuthorizedCodeSearch(WorkspaceService workspaces) {
    this.workspaces = java.util.Objects.requireNonNull(workspaces);
  }

  /** 搜索参数不含路径，避免由模型扩大用户授予的范围。 */
  public static void validateQuery(String query) {
    if (query == null
        || query.isBlank()
        || query.codePointCount(0, query.length()) > MAX_QUERY_CODE_POINTS
        || query.codePoints().anyMatch(Character::isISOControl)) {
      throw new IllegalArgumentException("搜索文本必须是 1 至 100 个字符的单行文本。");
    }
  }

  /** 任一文件读取失败时整体拒绝，不将部分结果误报为完整结果。 */
  public SearchResult search(
      String workspaceId, AuthorizedFileSet scope, String query, Runnable check) {
    validateQuery(query);
    java.util.Objects.requireNonNull(scope);
    java.util.Objects.requireNonNull(check);
    List<Match> matches = new ArrayList<>();
    var versions = new java.util.LinkedHashMap<String, String>();
    boolean truncated = false;
    for (String path : scope.paths()) {
      check.run();
      var file = workspaces.read(workspaceId, path);
      versions.put(path, file.sha256());
      var lines = file.content().lines().iterator();
      int lineNumber = 0;
      while (lines.hasNext()) {
        String line = lines.next();
        lineNumber++;
        if (lineNumber % 256 == 0) check.run();
        int offset = line.indexOf(query);
        if (offset < 0) continue;
        if (matches.size() == MAX_MATCHES) {
          truncated = true;
          break;
        }
        int total = line.codePointCount(0, line.length());
        int matchStart = line.codePointCount(0, offset);
        int start = Math.max(0, matchStart - 70);
        int end = Math.min(total, start + MAX_EXCERPT_CODE_POINTS);
        String excerpt =
            line.substring(line.offsetByCodePoints(0, start), line.offsetByCodePoints(0, end));
        matches.add(new Match(path, lineNumber, excerpt, start > 0 || end < total, file.sha256()));
      }
      // 即使达到匹配上限，也继续检查其他授权文件是否可读，避免将局部结果误报为完整搜索。
    }
    return new SearchResult(matches, truncated, versions);
  }

  /** 内容摘要与行文本来自同一次单文件读取；不能代替后续写入前的版本检查。 */
  public record Match(
      String path, int line, String excerpt, boolean lineTruncated, String sha256) {}

  /** {@code versions} 覆盖本次实际读取的全部授权文件，供恢复时核对版本。 */
  public record SearchResult(List<Match> matches, boolean truncated, Map<String, String> versions) {
    public SearchResult {
      matches = List.copyOf(matches);
      versions = Map.copyOf(versions);
    }

    /** 兼容不需要版本映射的旧调用。 */
    public SearchResult(List<Match> matches, boolean truncated) {
      this(matches, truncated, Map.of());
    }
  }
}
