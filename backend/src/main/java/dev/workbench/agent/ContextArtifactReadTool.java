package dev.workbench.agent;

import dev.workbench.control.ContextArtifactService;
import dev.workbench.framework.prompt.PromptResourceLoader;
import dev.workbench.framework.tool.ToolSpecification;
import java.util.List;
import java.util.Map;

/**
 * 受控只读工具：按需回读本次运行被 L1 卸载的上下文产物原文。
 *
 * <p>只接受本次运行自己产生的 {@code artifactRef}：引用本身不是授权凭证，跨运行或跨账号
 * 的引用一律拒绝（由 {@link ContextArtifactService#readForRun} 校验）。回读正文按上限截断，
 * 并在结果里显式标注 {@code truncated}，避免把半截正文当成全文使用。
 */
public final class ContextArtifactReadTool {
  private ContextArtifactReadTool() {}

  /** 单次回读的正文上限：足够覆盖绝大多数工具输出，又不会把上下文瞬间顶满。 */
  static final int MAX_CHARS = 24_000;

  public static ToolSpecification<Map<String, Object>> specification(
      ContextArtifactService artifacts, String runId, String locale) {
    boolean english = PromptResourceLoader.isEnglish(locale);
    return new ToolSpecification<>(
        "context_artifact_read",
        english
            ? "Read back the full body of a large tool output that was offloaded to an artifact in THIS run. Only references produced by the current run are accepted."
            : "回读本次运行中因上下文压缩被卸载为 Artifact 的大段工具输出原文。只接受本次运行自己产生的引用。",
        List.of(
            new ToolSpecification.Parameter(
                "reference",
                english
                    ? "Artifact reference such as art-1a2b3c."
                    : "Artifact 引用，形如 art-1a2b3c。")),
        List.of(runId),
        english ? "Reading back an offloaded artifact." : "正在回读被卸载的上下文产物。",
        arguments -> {
          String reference = arguments.get("reference");
          if (reference == null || reference.isBlank())
            throw new IllegalArgumentException(
                english ? "The artifact reference must not be blank." : "Artifact 引用不能为空。");
          if (runId == null || runId.isBlank())
            throw new IllegalArgumentException(
                english
                    ? "This run has no identifier, so artifacts cannot be read back."
                    : "当前运行缺少标识，不能回读产物。");
        },
        (arguments, check) -> {
          check.run();
          var payload =
              artifacts.readForRun(runId, arguments.get("reference").strip(), MAX_CHARS);
          check.run();
          return payload;
        },
        (arguments, payload) -> {
          Object chars = payload == null ? null : payload.get("charCount");
          Object truncated = payload == null ? null : payload.get("truncated");
          boolean cut = Boolean.TRUE.equals(truncated);
          return english
              ? "Artifact read back; original characters: "
                  + chars
                  + (cut ? "; the body was truncated for this call." : ".")
              : "已回读产物，原始字符数："
                  + chars
                  + (cut ? "；本次返回已截断。" : "。");
        },
        null);
  }
}
