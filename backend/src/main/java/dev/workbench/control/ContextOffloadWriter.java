package dev.workbench.control;

import dev.workbench.framework.context.CompactionScope;
import dev.workbench.framework.context.ContextArtifactStore;
import dev.workbench.framework.context.ContextBreakdown;
import dev.workbench.framework.context.ContextMessage;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * L1 工具输出卸载：把超长工具结果完整落盘为 Artifact，正文只保留受控引用与 Query-Aware 高相关摘录。
 *
 * <p>没有可用产物存储时仍生成带运行与归属种子的确定性引用，避免跨用户撞同一引用。
 */
final class ContextOffloadWriter {
  private static final int L1_MIN_CHARS = 1_500;
  private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
      new com.fasterxml.jackson.databind.ObjectMapper();

  private final List<ContextArtifactStore> artifactStores;

  ContextOffloadWriter(List<ContextArtifactStore> artifactStores) {
    this.artifactStores = artifactStores == null ? List.of() : List.copyOf(artifactStores);
  }

  /** 卸载早于最近若干轮的超长工具结果；没有可卸载内容时原样返回。 */
  Result write(
      List<ContextMessage> messages, int keepTurns, String focus, CompactionScope scope) {
    int keepFrom = ContextWindowBoundaries.roundStart(messages, keepTurns);
    var updated = new ArrayList<ContextMessage>(messages.size());
    var ids = new ArrayList<String>();
    var refs = new ArrayList<String>();
    var details = new ArrayList<ContextBreakdown.OffloadDetail>();
    var preview = new StringBuilder();
    boolean changed = false;
    for (int index = 0; index < messages.size(); index++) {
      var message = messages.get(index);
      if (index < keepFrom
          && message.toolResult()
          && message.content() != null
          && message.content().length() > L1_MIN_CHARS) {
        String id = message.toolCallId() == null ? "tool-result-" + index : message.toolCallId();
        var excerpt =
            ContextExcerptSelector.select(
                message.content(), focus, excerptBudget(message.content()));
        String ref = persistArtifact(message, id, excerpt, scope);
        String replacement = renderOffloaded(message, id, ref, excerpt);
        updated.add(new ContextMessage(message.role(), message.name(), replacement, id));
        ids.add(id);
        refs.add(ref);
        details.add(
            new ContextBreakdown.OffloadDetail(
                id, ref, message.content().length(), replacement.length()));
        if (preview.isEmpty()) preview.append(excerpt.preview());
        changed = true;
        continue;
      }
      updated.add(message);
    }
    return new Result(updated, ids, refs, preview.toString(), changed, details);
  }

  private static int excerptBudget(String content) {
    return Math.max(600, Math.min(6_000, content.length() / 5));
  }

  private String persistArtifact(
      ContextMessage message, String toolCallId, ContextExcerptSelector.Excerpt excerpt, CompactionScope scope) {
    String title = message.name() == null ? "tool-output" : message.name();
    String outlineJson = outlineJson(excerpt);
    for (var store : artifactStores) {
      if (store == null || !store.available()) continue;
      String ref =
          store.save(
              new ContextArtifactStore.Artifact(
                  scope.runId(),
                  scope.ownerScopeKey(),
                  toolCallId,
                  title,
                  message.content(),
                  outlineJson));
      if (ref != null && !ref.isBlank()) return ref;
    }
    // 没有可用存储时仍要生成确定性引用，但种子必须带上运行与归属，避免跨用户撞同一引用。
    return deterministicReference(
        message.content(), title + toolCallId + scope.runId() + scope.ownerScopeKey());
  }

  private static String deterministicReference(String content, String seed) {
    try {
      var digest =
          MessageDigest.getInstance("SHA-256")
              .digest((seed + "\n" + content).getBytes(StandardCharsets.UTF_8));
      return "art-" + HexFormat.of().formatHex(digest).substring(0, 16);
    } catch (Exception e) {
      return "art-" + Integer.toHexString((seed + content).hashCode());
    }
  }

  private static String renderOffloaded(
      ContextMessage original, String id, String ref, ContextExcerptSelector.Excerpt excerpt) {
    int lines = (int) original.content().lines().count();
    var text = new StringBuilder();
    text.append("[Offloaded: toolCallId=")
        .append(id)
        .append(", artifactRef=")
        .append(ref)
        .append(", chars=")
        .append(original.content().length())
        .append(", lines=")
        .append(lines)
        .append(", 可按 artifactRef 回查]\n");
    text.append("[ArtifactRef: ")
        .append(ref)
        .append(", 已保留最相关 ")
        .append(excerpt.windows().size())
        .append(" 段，其余可按需回查]\n");
    appendOutline(text, excerpt.outline());
    text.append("【高相关摘录】\n");
    for (var window : excerpt.windows()) {
      text.append("- L")
          .append(window.startLine())
          .append('-')
          .append(window.endLine())
          .append(": ")
          .append(ContextTextSignals.compact(window.text(), 620))
          .append('\n');
    }
    return text.toString().strip();
  }

  private static void appendOutline(
      StringBuilder text, List<ContextExcerptSelector.Outline> outline) {
    if (outline.isEmpty()) return;
    text.append("【结构化大纲】\n");
    for (var line :
        outline.subList(0, Math.min(ContextExcerptSelector.OUTLINE_LIMIT, outline.size())))
      text.append("- L")
          .append(line.line())
          .append(": ")
          .append(ContextTextSignals.compact(line.text(), 170))
          .append('\n');
  }

  private static String outlineJson(ContextExcerptSelector.Excerpt excerpt) {
    try {
      var rows = new ArrayList<Map<String, Object>>();
      for (var line : excerpt.outline())
        rows.add(
            Map.of(
                "line", line.line(), "text", ContextTextSignals.compact(line.text(), 300)));
      return JSON.writeValueAsString(rows);
    } catch (Exception e) {
      return "[]";
    }
  }

  /** 一次 L1 卸载的产物：替换后的消息、卸载标识、产物引用、预览与逐条明细。 */
  record Result(
      List<ContextMessage> messages,
      List<String> ids,
      List<String> refs,
      String preview,
      boolean changed,
      List<ContextBreakdown.OffloadDetail> details) {}
}
