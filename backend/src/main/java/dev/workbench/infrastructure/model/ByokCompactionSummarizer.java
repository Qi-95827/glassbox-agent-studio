package dev.workbench.infrastructure.model;

import dev.workbench.framework.context.CompactionSummarizer;
import dev.workbench.framework.context.ContextMessage;
import dev.workbench.modelconnection.ModelConnectionService;
import dev.workbench.modelconnection.ModelProviderType;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * BYOK 模式下的 L2 结构化锚点摘要器。
 *
 * <p>复用用户默认模型连接，要求模型只输出四段强约束检查点；失败或连接不可用时返回空串，
 * 由压缩器降级为本地关键句抽取。输入正文有字符上限，避免摘要调用本身撑爆上下文。
 */
@Component
public class ByokCompactionSummarizer implements CompactionSummarizer {
  private static final int MAX_HISTORY_CHARS = 24_000;
  private static final int MAX_TOKENS = 900;

  private final ModelConnectionService connections;
  private final boolean enabled;

  public ByokCompactionSummarizer(
      ModelConnectionService connections,
      @Value("${workbench.context.summarizer.enabled:true}") boolean enabled) {
    this.connections = connections;
    this.enabled = enabled;
  }

  @Override
  public boolean isAvailable() {
    if (!enabled) return false;
    try {
      return connections.defaultConnectionId().isPresent();
    } catch (RuntimeException e) {
      return false;
    }
  }

  @Override
  public String summarize(
      String focusQuery, String previousCheckpoint, List<ContextMessage> messages) {
    if (messages == null || messages.isEmpty()) return "";
    try {
      var connectionId = connections.defaultConnectionId().orElse(null);
      if (connectionId == null) return "";
      var connection = connections.resolveForExecution(connectionId);
      var model = UserModelConnectionFactory.model(connection);
      var instructions =
          """
          你是上下文压缩器。请把中间历史压缩为强约束的结构化检查点，只输出以下四段，禁止寒暄：
          ## confirmed_facts
          ## active_constraints
          ## file_and_tool_state
          ## open_questions
          每段最多 8 条，每条以 "- " 开头；保留关键数值、时间、文件名、报错码与工具回执。
          不得编造历史中不存在的事实；不得输出任何密钥、令牌或完整凭据。
          """;
      var builder = new StringBuilder();
      builder.append("当前目标：").append(focusQuery == null ? "" : focusQuery).append("\n\n");
      if (previousCheckpoint != null && !previousCheckpoint.isBlank())
        builder.append("上一轮检查点（需增量合并，不要重复列已确认事实）：\n")
            .append(previousCheckpoint)
            .append("\n\n");
      builder.append("本轮新增历史：\n");
      for (var message : messages) {
        builder
            .append('[')
            .append(message.role())
            .append(message.name() == null ? "" : " " + message.name())
            .append("] ")
            .append(oneLine(message.content()))
            .append('\n');
        if (builder.length() > MAX_HISTORY_CHARS) break;
      }
      var request =
          List.of(
              Msg.builder().role(MsgRole.SYSTEM).textContent(instructions).build(),
              Msg.builder().role(MsgRole.USER).textContent(builder.toString()).build());
      var options =
          GenerateOptions.builder().maxTokens(MAX_TOKENS).maxCompletionTokens(MAX_TOKENS);
      if (officialDeepSeek(connection)) {
        options.additionalBodyParams(Map.of("thinking", Map.of("type", "disabled")));
      }
      ChatResponse response =
          model.stream(request, List.of(), options.build()).blockLast(Duration.ofSeconds(60));
      if (response == null) return "";
      String text =
          response.getContent() == null
              ? ""
              : response.getContent().stream()
                  .filter(TextBlock.class::isInstance)
                  .map(TextBlock.class::cast)
                  .map(TextBlock::getText)
                  .filter(value -> value != null && !value.isBlank())
                  .collect(Collectors.joining())
                  .strip();
      return text.contains("confirmed_facts") ? text : "";
    } catch (Exception e) {
      return "";
    }
  }

  private static boolean officialDeepSeek(
      dev.workbench.modelconnection.RuntimeModelConnection connection) {
    return connection.providerType() == ModelProviderType.DEEPSEEK
        && "api.deepseek.com".equalsIgnoreCase(URI.create(connection.baseUrl()).getHost());
  }

  private static String oneLine(String value) {
    return value == null ? "" : value.replace('\n', ' ').strip();
  }
}
