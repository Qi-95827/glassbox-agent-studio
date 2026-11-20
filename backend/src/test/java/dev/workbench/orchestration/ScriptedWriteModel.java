package dev.workbench.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.workbench.domain.AgentRole;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import reactor.core.publisher.Flux;

/**
 * 确定性写入模型：按节点写文件集合依次读取版本并写入固定内容。
 *
 * <p>只用于自动化验收多文件交付；它必须通过真实工具网关和节点授权，不会绕过路径或版本检查。
 */
final class ScriptedWriteModel implements Model {
  private static final ObjectMapper JSON = new ObjectMapper();
  private final AgentRole role;
  private final List<String> readFiles;
  private final List<String> writeFiles;
  private final Map<String, String> contents;
  private final java.util.Set<String> skipped;
  private final AtomicInteger calls = new AtomicInteger();

  ScriptedWriteModel(
      AgentRole role,
      List<String> readFiles,
      List<String> writeFiles,
      Map<String, String> contents) {
    this(role, readFiles, writeFiles, contents, java.util.Set.of());
  }

  ScriptedWriteModel(
      AgentRole role,
      List<String> readFiles,
      List<String> writeFiles,
      Map<String, String> contents,
      java.util.Set<String> skipped) {
    this.role = role;
    this.readFiles = List.copyOf(readFiles);
    this.writeFiles = List.copyOf(writeFiles);
    this.contents = Map.copyOf(contents);
    this.skipped = java.util.Set.copyOf(skipped);
  }

  @Override
  public String getModelName() {
    return "scripted-bubble-sort";
  }

  @Override
  public Flux<ChatResponse> stream(
      List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
    if (role != AgentRole.CODER) {
      if (role == AgentRole.ANALYST && !readFiles.isEmpty() && calls.getAndIncrement() == 0)
        return Flux.just(tool("read_authorized_file", readArguments(0)));
      return Flux.just(
          text(
              role == AgentRole.REVIEWER
                  ? "审查完成：已收到两个开发节点的交接与写入回执；脚本行为仍需人工浏览器验收。"
                  : "分析完成：约定输入数组、单步比较和最终结果的展示方式；未执行测试。"));
    }
    if (writeFiles.stream().anyMatch(skipped::contains))
      return Flux.just(text("开发完成：未写出必需文件（测试夹具）。"));
    int call = calls.getAndIncrement();
    int index = call / 2;
    if (index >= writeFiles.size())
      return Flux.just(text("开发完成：已按授权写入 " + String.join("、", writeFiles) + "；仍需审查和浏览器验收。"));
    String path = writeFiles.get(index);
    if (call % 2 == 0) return Flux.just(tool("read_authorized_file", readArguments(index)));
    String sha = lastReadSha(messages);
    if (sha == null) return Flux.error(new IllegalStateException("脚本模型没有读到文件版本。"));
    var input = new java.util.LinkedHashMap<String, Object>();
    if (writeFiles.size() > 1 || readFiles.size() > 1) input.put("path", path);
    input.put("expectedSha256", sha);
    input.put("content", contents.get(path));
    return Flux.just(tool("write_authorized_file", input));
  }

  private Map<String, Object> readArguments(int index) {
    if (readFiles.size() == 1) return Map.of();
    // 多文件读取使用写入集合中的下一个文件；分析节点读取第一个授权文件。
    String path =
        writeFiles.isEmpty()
            ? readFiles.getFirst()
            : writeFiles.get(Math.min(index, writeFiles.size() - 1));
    return Map.of("path", path);
  }

  private static String lastReadSha(List<Msg> messages) {
    for (int i = messages.size() - 1; i >= 0; i--) {
      for (var block : messages.get(i).getContentBlocks(ToolResultBlock.class)) {
        for (var output : block.getOutput()) {
          if (!(output instanceof TextBlock text) || text.getText() == null) continue;
          try {
            var value = JSON.readTree(text.getText());
            if (value != null && value.isTextual()) value = JSON.readTree(value.asText());
            var sha = value == null ? null : value.path("data").path("sha256").asText(null);
            if (sha != null && sha.matches("[0-9a-f]{64}")) return sha;
          } catch (com.fasterxml.jackson.core.JsonProcessingException ignored) {
            // 继续查找上一条工具结果。
          }
        }
      }
    }
    return null;
  }

  private static ChatResponse tool(String name, Map<String, Object> input) {
    try {
      return ChatResponse.builder()
          .content(
              List.of(
                  ToolUseBlock.builder()
                      .id("scripted-" + java.util.UUID.randomUUID())
                      .name(name)
                      .input(input)
                      .content(JSON.writeValueAsString(input))
                      .build()))
          .build();
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }

  private static ChatResponse text(String value) {
    return ChatResponse.builder().content(List.of(TextBlock.builder().text(value).build())).build();
  }
}
