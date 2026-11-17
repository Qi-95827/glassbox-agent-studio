package dev.workbench.workspace;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;

/** 为用户提供受限的 Git 已跟踪文件候选；查询本身不授予 Agent 权限。 */
@Service
public final class WorkspaceFileCatalog {
  private static final int MAX_RESULTS = 100;
  private final WorkspaceService workspaces;

  public WorkspaceFileCatalog(WorkspaceService workspaces) {
    this.workspaces = workspaces;
  }

  /** 搜索路径而非文件内容，结果可能因工作区变化而过期。 */
  public Candidates find(String workspaceId, String keyword) {
    String query = keyword == null ? "" : keyword;
    if (query.codePointCount(0, query.length()) > 100
        || query.codePoints().anyMatch(Character::isISOControl)) {
      throw new IllegalArgumentException("路径关键词不能超过 100 字，且不能包含控制字符。");
    }
    var workspace = workspaces.get(workspaceId);
    // -z 禁止 Git 对中文和特殊字符进行转义；参数分离避免将用户输入交给 shell。
    String output =
        workspaces.runGit(Path.of(workspace.workspacePath()), "ls-files", "-z", "--cached", "--");
    String needle = query.toLowerCase(Locale.ROOT);
    var unique = new LinkedHashSet<String>();
    for (String path : output.split(String.valueOf((char) 0))) {
      if (!path.isEmpty() && path.toLowerCase(Locale.ROOT).contains(needle)) unique.add(path);
    }
    List<String> paths = new ArrayList<>();
    boolean truncated = false;
    for (String path : unique) {
      if (!workspaces.isFileCandidate(workspace, path)) continue;
      if (paths.size() == MAX_RESULTS) {
        truncated = true;
        break;
      }
      paths.add(path);
    }
    return new Candidates(paths, truncated);
  }

  /** 截断仅表示满足条件的候选超过返回上限。 */
  public record Candidates(List<String> paths, boolean truncated) {
    public Candidates {
      paths = List.copyOf(paths);
    }
  }
}
