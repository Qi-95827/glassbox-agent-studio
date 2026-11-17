package dev.workbench.workspace;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;

/** 用户显式提供的现有文件相对路径集合；不授权目录或通配范围。 */
public record AuthorizedFileSet(List<String> paths) {
  public AuthorizedFileSet {
    if (paths == null || paths.isEmpty() || paths.size() > 20)
      throw new IllegalArgumentException("请选择 1 至 20 个授权文件。");
    var normalized = new LinkedHashSet<String>();
    for (String path : paths) {
      if (!normalized.add(normalize(path))) throw new IllegalArgumentException("授权文件不能重复。");
    }
    paths = List.copyOf(normalized);
  }

  /** 将单个相对路径规范化；实际文件安全仍需工作区服务重复检查。 */
  public static String normalize(String raw) {
    if (raw == null
        || raw.isBlank()
        || raw.length() > 1000
        || raw.codePoints().anyMatch(Character::isISOControl))
      throw new IllegalArgumentException("授权文件路径无效。");
    String input = raw.replace('\\', '/');
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

  /** 模型只能选择已授权集合中的文件，不能借路径规范化扩大授权。 */
  public String require(String raw) {
    String path = normalize(raw);
    if (!paths.contains(path)) throw new IllegalArgumentException("文件不在授权范围内。");
    return path;
  }
}
