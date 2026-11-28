package dev.workbench.control;

import dev.workbench.memory.HybridRanker;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Query-Aware 摘录选择：把超长正文切成段窗口，按相关性挑出最相关片段并抽取结构化大纲。
 *
 * <p>这是 L1「不再死留头尾」与 L3 缩短工具结果共用的纯计算部分，不落盘、不读写归属。
 */
final class ContextExcerptSelector {
  static final int L1_WINDOW_CHARS = 520;
  static final int L1_PREVIEW_CHARS = 1_200;
  static final int OUTLINE_LIMIT = 18;

  /** 大纲条目：原文件行号与单行文本。 */
  record Outline(int line, String text) {}

  /** 摘录窗口：覆盖的起止行号与正文。 */
  record Window(int startLine, int endLine, String text) {}

  /** 一次摘录结果：保留窗口与结构化大纲。 */
  record Excerpt(List<Window> windows, List<Outline> outline) {
    String preview() {
      if (windows.isEmpty()) return "";
      var text = new StringBuilder();
      for (var window : windows) {
        text.append("L")
            .append(window.startLine())
            .append('-')
            .append(window.endLine())
            .append(": ")
            .append(ContextTextSignals.compact(window.text(), 220))
            .append('\n');
      }
      String value = text.toString();
      return value.length() <= L1_PREVIEW_CHARS
          ? value
          : value.substring(0, L1_PREVIEW_CHARS - 1) + "…";
    }
  }

  private record ScoredWindow(Window window, double score, int index) {}

  private ContextExcerptSelector() {}

  /** 按相关性挑选不超过预算的窗口；空正文返回空摘录。 */
  static Excerpt select(String content, String focus, int budgetChars) {
    if (content == null || content.isBlank()) return new Excerpt(List.of(), List.of());
    var windows = windows(content);
    var outline = outline(content);
    if (windows.isEmpty()) return new Excerpt(List.of(), outline);
    var inputs = new ArrayList<HybridRanker.Input>();
    for (int index = 0; index < windows.size(); index++)
      inputs.add(new HybridRanker.Input("w" + index, windows.get(index).text(), 0));
    var ranked = HybridRanker.rank(inputs, focus);
    var scored = new ArrayList<ScoredWindow>();
    for (int index = 0; index < windows.size(); index++) {
      var window = windows.get(index);
      double lexical = ranked.containsKey("w" + index) ? ranked.get("w" + index).bm25() : 0;
      double score = 0.75 * lexical + 0.25 * ContextTextSignals.structuralBoost(window.text());
      scored.add(new ScoredWindow(window, score, index));
    }
    scored.sort(
        Comparator.comparingDouble(ScoredWindow::score)
            .reversed()
            .thenComparingInt(ScoredWindow::index));
    var chosen = new ArrayList<Window>();
    int used = 0;
    for (var item : scored) {
      if (!chosen.isEmpty() && used + item.window().text().length() > budgetChars) continue;
      chosen.add(item.window());
      used += item.window().text().length();
      if (used >= budgetChars) break;
    }
    if (chosen.isEmpty()) chosen.add(windows.getFirst());
    chosen.sort(Comparator.comparingInt(Window::startLine));
    return new Excerpt(List.copyOf(chosen), outline);
  }

  /** 按空行与长度把正文切成段落窗口，行号保持在原文坐标系。 */
  private static List<Window> windows(String content) {
    String text = content.replace("\r\n", "\n").replace('\r', '\n');
    var lines = text.split("\n", -1);
    var result = new ArrayList<Window>();
    var paragraph = new StringBuilder();
    int start = -1;
    for (int index = 0; index < lines.length; index++) {
      String line = lines[index];
      if (line.isBlank()) {
        flushWindow(result, paragraph, start, index);
        start = -1;
        continue;
      }
      if (start < 0) start = index + 1;
      paragraph.append(line).append('\n');
      if (paragraph.length() >= L1_WINDOW_CHARS * 2) {
        flushWindow(result, paragraph, start, index + 1);
        start = -1;
      }
    }
    flushWindow(result, paragraph, start, lines.length);
    if (result.isEmpty() && !text.isBlank())
      result.add(new Window(1, 1 + (int) text.chars().filter(c -> c == '\n').count(), text));
    return List.copyOf(result);
  }

  private static void flushWindow(
      List<Window> output, StringBuilder paragraph, int startLine, int endLine) {
    if (paragraph.length() == 0 || startLine < 1) {
      paragraph.setLength(0);
      return;
    }
    String body = paragraph.toString().strip();
    paragraph.setLength(0);
    if (body.isBlank()) return;
    if (body.length() <= L1_WINDOW_CHARS) {
      output.add(new Window(startLine, Math.max(startLine, endLine), body));
      return;
    }
    int offset = 0;
    while (offset < body.length()) {
      int end = Math.min(body.length(), offset + L1_WINDOW_CHARS);
      if (end < body.length()) {
        int newline = body.lastIndexOf('\n', end);
        if (newline > offset + L1_WINDOW_CHARS / 2) end = newline;
      }
      String part = body.substring(offset, end).strip();
      if (!part.isBlank()) {
        int lineOffset = (int) body.substring(0, offset).chars().filter(c -> c == '\n').count();
        output.add(
            new Window(
                startLine + lineOffset,
                startLine + lineOffset + (int) part.chars().filter(c -> c == '\n').count(),
                part));
      }
      if (end >= body.length()) break;
      offset = Math.max(offset + 1, end - 60);
    }
  }

  /** 抽取标题、代码行与含结构信号的提纲行，数量受 {@link #OUTLINE_LIMIT} 约束。 */
  private static List<Outline> outline(String content) {
    var result = new ArrayList<Outline>();
    var lines = content.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
    for (int index = 0; index < lines.length && result.size() < OUTLINE_LIMIT * 2; index++) {
      String line = lines[index].strip();
      if (line.isBlank()) continue;
      String lower = line.toLowerCase(java.util.Locale.ROOT);
      boolean heading = line.matches("^#{1,6}\\s+.+") || line.matches("^[=~-]{3,}$");
      boolean code =
          line.matches(
                  "^(public|private|protected|static|class|interface|record|def|function|export|const|let)\\b.*")
              || line.contains("=>")
              || line.endsWith("{");
      boolean signal =
          ContextTextSignals.hasPath(line)
              || ContextTextSignals.hasException(line)
              || lower.contains("todo");
      if (heading || code || signal) result.add(new Outline(index + 1, line));
    }
    return List.copyOf(result);
  }

  /** 渲染摘录正文段落，供硬保护缩短工具结果时复用。 */
  static String renderExcerptBody(Excerpt excerpt) {
    var text = new StringBuilder();
    for (var window : excerpt.windows()) {
      text.append("- L")
          .append(window.startLine())
          .append('-')
          .append(window.endLine())
          .append(": ")
          .append(ContextTextSignals.compact(window.text(), 620))
          .append('\n');
    }
    return text.toString();
  }
}
