package dev.workbench.memory.knowledge;

import java.util.ArrayList;
import java.util.List;

/**
 * Markdown/代码感知的重叠窗口分块器。
 *
 * <p>按标题、空行段落与代码围栏切分；每块约 400~600 tokens（以字符/4 保守估算），重叠约 80 tokens。
 * 保留 {@code sectionTitle} 与行号范围，便于 RAG 命中后精确回查。该类型无状态，可安全复用于评测。
 */
public final class DocumentChunker {
  public static final int DEFAULT_MAX_CHARS = 2_000;
  public static final int DEFAULT_OVERLAP_CHARS = 320;

  private final int maxChars;
  private final int overlapChars;

  public DocumentChunker() {
    this(DEFAULT_MAX_CHARS, DEFAULT_OVERLAP_CHARS);
  }

  public DocumentChunker(int maxChars, int overlapChars) {
    this.maxChars = Math.max(400, maxChars);
    this.overlapChars = Math.max(0, Math.min(this.maxChars / 2, overlapChars));
  }

  public record Chunk(
      int index, String sectionTitle, String content, int tokenEstimate, int startLine, int endLine) {}

  public List<Chunk> chunk(String raw) {
    if (raw == null || raw.isBlank()) return List.of();
    String text = raw.replace("\r\n", "\n").replace('\r', '\n');
    var blocks = blocks(text);
    var chunks = new ArrayList<Chunk>();
    var current = new StringBuilder();
    var currentStart = -1;
    var currentEnd = -1;
    String section = "";
    for (var block : blocks) {
      if (block.heading && !block.text.isBlank()) {
        // 标题是结构性边界：先以旧章节落块，再切换章节，避免片段被标成下一章。
        if (current.length() > 0) {
          chunks.add(chunk(chunks.size(), section, current.toString(), currentStart, currentEnd));
          current.setLength(0);
          currentStart = -1;
          currentEnd = -1;
        }
        section = headingTitle(block.text);
      }
      if (block.text.length() > maxChars) {
        if (current.length() > 0) {
          chunks.add(chunk(chunks.size(), section, current.toString(), currentStart, currentEnd));
          current.setLength(0);
        }
        splitWindow(chunks, section, block);
        currentStart = -1;
        currentEnd = -1;
        continue;
      }
      if (current.length() > 0 && current.length() + block.text.length() + 2 > maxChars) {
        chunks.add(chunk(chunks.size(), section, current.toString(), currentStart, currentEnd));
        String overlap = tail(current.toString(), overlapChars);
        current.setLength(0);
        if (!overlap.isBlank()) current.append(overlap).append('\n');
        currentStart = block.startLine;
      }
      if (currentStart < 0) currentStart = block.startLine;
      current.append(block.text).append('\n');
      currentEnd = Math.max(currentEnd, block.endLine);
    }
    if (current.length() > 0)
      chunks.add(chunk(chunks.size(), section, current.toString(), currentStart, Math.max(currentStart, currentEnd)));
    return List.copyOf(chunks);
  }

  private void splitWindow(List<Chunk> chunks, String section, Block block) {
    String body = block.text;
    int step = Math.max(1, maxChars - overlapChars);
    int start = 0;
    while (start < body.length()) {
      int end = Math.min(body.length(), start + maxChars);
      if (end < body.length()) {
        int newline = body.lastIndexOf('\n', end);
        if (newline > start + maxChars / 2) end = newline;
      }
      String content = body.substring(start, end).strip();
      if (!content.isBlank()) {
        int startLine = block.startLine + countLines(body, start);
        int endLine = startLine + countLines(content, content.length());
        chunks.add(
            new Chunk(
                chunks.size(),
                section,
                content,
                Math.max(1, (content.length() + 3) / 4),
                Math.max(1, startLine),
                Math.max(1, endLine)));
      }
      if (end >= body.length()) break;
      start = Math.max(start + step, end - overlapChars);
    }
  }

  private static int countLines(String text, int endExclusive) {
    int lines = 0;
    for (int index = 0; index < endExclusive && index < text.length(); index++)
      if (text.charAt(index) == '\n') lines++;
    return lines;
  }

  private Chunk chunk(int index, String section, String content, int startLine, int endLine) {
    String trimmed = content.strip();
    int tokens = Math.max(1, (trimmed.length() + 3) / 4);
    return new Chunk(index, section, trimmed, tokens, Math.max(1, startLine), Math.max(1, endLine));
  }

  private record Block(String text, boolean heading, int startLine, int endLine) {}

  private static List<Block> blocks(String text) {
    var lines = text.split("\n", -1);
    var blocks = new ArrayList<Block>();
    var paragraph = new StringBuilder();
    int paragraphStart = -1;
    boolean inFence = false;
    for (int index = 0; index < lines.length; index++) {
      String line = lines[index];
      String trimmed = line.strip();
      if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) inFence = !inFence;
      boolean heading = !inFence && trimmed.matches("^#{1,6}\\s+.+");
      boolean blank = trimmed.isEmpty() && !inFence;
      if (heading) {
        flush(blocks, paragraph, paragraphStart, index);
        paragraphStart = -1;
        blocks.add(new Block(line, true, index + 1, index + 1));
        continue;
      }
      if (blank) {
        flush(blocks, paragraph, paragraphStart, index);
        paragraphStart = -1;
        continue;
      }
      if (paragraphStart < 0) paragraphStart = index + 1;
      paragraph.append(line).append('\n');
      if (paragraph.length() > DEFAULT_MAX_CHARS * 2) {
        // 超长代码块或单段正文：先按当前边界落块，避免无限累积。
        flush(blocks, paragraph, paragraphStart, index + 1);
        paragraphStart = -1;
      }
    }
    flush(blocks, paragraph, paragraphStart, lines.length);
    return List.copyOf(blocks);
  }

  private static void flush(List<Block> blocks, StringBuilder paragraph, int startLine, int endLine) {
    if (paragraph.length() == 0 || startLine < 1) {
      paragraph.setLength(0);
      return;
    }
    String body = paragraph.toString().strip();
    if (!body.isBlank()) blocks.add(new Block(body, false, startLine, Math.max(startLine, endLine)));
    paragraph.setLength(0);
  }

  static String headingTitle(String heading) {
    String title = heading.replaceFirst("^#{1,6}\\s+", "").strip();
    return title.length() <= 280 ? title : title.substring(0, 279) + "…";
  }

  private static String tail(String text, int size) {
    if (size <= 0 || text.length() <= size) return size <= 0 ? "" : text;
    int start = text.length() - size;
    int newline = text.indexOf('\n', start);
    if (newline >= 0 && newline + 1 < text.length()) start = newline + 1;
    return text.substring(start).strip();
  }
}
