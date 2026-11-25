package dev.workbench.workspace;

import dev.workbench.framework.i18n.LocalizedMessages;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;

/** 用户显式提供的现有文件相对路径集合；不授权目录或通配范围。 */
public record AuthorizedFileSet(List<String> paths) {
  /** 手工选择文件的数量上限；目录展开后的有效文件另有 {@link #MAX_SCOPE} 上限。 */
  public static final int MAX_EXPLICIT = 20;

  /** 目录（含整个工作区 {@code "."}）展开后的服务端有效文件上限。 */
  public static final int MAX_SCOPE = 200;

  public AuthorizedFileSet {
    if (paths == null || paths.isEmpty() || paths.size() > MAX_EXPLICIT)
      throw new IllegalArgumentException("请选择 1 至 " + MAX_EXPLICIT + " 个授权文件。");
    var normalized = new LinkedHashSet<String>();
    for (String path : paths) {
      if (!normalized.add(normalize(path))) throw new IllegalArgumentException("授权文件不能重复。");
    }
    paths = List.copyOf(normalized);
  }

  /** 将单个相对路径规范化；实际文件安全仍需工作区服务重复检查。 */
  public static String normalize(String raw) {
    String input = requireRaw(raw, "授权文件路径无效。");
    if (input.matches("^[a-zA-Z]:.*")) throw new IllegalArgumentException("必须使用相对文件路径。");
    Path path = Path.of(input).normalize();
    if (path.isAbsolute()
        || path.getRoot() != null
        || path.startsWith("..")
        || path.toString().isBlank()
        || path.toString().equals(".")) throw new IllegalArgumentException("文件路径不在工作区内。");
    for (Path part : path) {
      if (part.toString().equalsIgnoreCase(".git"))
        throw new IllegalArgumentException("不允许访问 Git 元数据。");
    }
    return path.toString().replace('\\', '/');
  }

  /**
   * 将目录范围规范化；{@code "."} 与 {@code "./"} 表示当前工作区根目录。
   *
   * <p>其余规则与文件路径一致：必须是工作区内相对路径，不能包含 {@code ..} 或 {@code .git}。
   */
  public static String normalizeDirectory(String raw) {
    String input = requireRaw(raw, "授权目录路径无效。");
    if (input.matches("^[a-zA-Z]:.*")) throw new IllegalArgumentException("必须使用相对目录路径。");
    String relative = input;
    while (relative.startsWith("./")) relative = relative.substring(2);
    if (relative.isEmpty() || relative.equals(".")) return ".";
    return normalize(relative);
  }

  /**
   * 服务端展开后的文件集合：仍然逐条做同样的路径校验，但允许超过手工选择上限。
   *
   * <p>只用于服务端已冻结的目录展开结果，客户端提交的显式文件仍由 {@link #AuthorizedFileSet(List)} 限制。
   */
  public static List<String> normalizeScope(List<String> paths) {
    if (paths == null || paths.isEmpty()) throw new IllegalArgumentException("授权文件不能为空。");
    if (paths.size() > MAX_SCOPE)
      throw new IllegalArgumentException(tooManyFilesMessage(MAX_SCOPE));
    var normalized = new LinkedHashSet<String>();
    for (String path : paths) normalized.add(normalize(path));
    return List.copyOf(normalized);
  }

  /** 模型只能选择已授权集合中的文件，不能借路径规范化扩大授权。 */
  public String require(String raw) {
    String path = normalize(raw);
    if (!paths.contains(path)) throw new IllegalArgumentException("文件不在授权范围内。");
    return path;
  }

  /** 目录展开结果超过平台上限时的中英文提示，引导用户缩小范围而不是静默截断。 */
  public static String tooManyFilesMessage(int limit) {
    return LocalizedMessages.get("validation.task.scopeTooManyFiles", limit);
  }

  private static String requireRaw(String raw, String message) {
    if (raw == null
        || raw.isBlank()
        || raw.length() > 1000
        || raw.codePoints().anyMatch(Character::isISOControl))
      throw new IllegalArgumentException(message);
    return raw.replace('\\', '/');
  }
}
