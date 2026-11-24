package dev.workbench.infrastructure.model;

import dev.workbench.framework.context.CompactionScope;
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
 * <p>只使用调用方显式透传的运行连接（{@link CompactionScope#modelConnectionId()}）：摘要运行在 Agent 工作线程，没有 HTTP
 * 会话，任何「读取默认连接」的回退都会落到 {@code LOCAL} 账号，盗用他人的密钥与 额度并把敏感对话发往错误账号。没有连接时直接降级为本地规则摘要。
 *
 * <p>摘要调用本身产生的用量通过 {@link CompactionScope#usage()} 计入当前运行的费用台账，避免成为预算
 * 之外的幽灵开销。输入正文有字符上限，避免摘要调用本身撑爆上下文。
 */
@Component
public class ByokCompactionSummarizer implements CompactionSummarizer {
  private static final int MAX_HISTORY_CHARS = 24_000;
  private static final int MAX_TOKENS = 900;
  private static final Duration SUMMARY_TIMEOUT = Duration.ofSeconds(60);

  private final ModelConnectionService connections;
  private final boolean enabled;

  public ByokCompactionSummarizer(
      ModelConnectionService connections,
      @Value("${workbench.context.summarizer.enabled:true}") boolean enabled) {
    this.connections = connections;
    this.enabled = enabled;
  }

  /** 是否启用真实模型摘要；具体连接由每次调用显式传入，不能在这里探测默认连接。 */
  @Override
  public boolean isAvailable() {
    return enabled;
  }

  /**
   * 无归属信息的旧调用：不做真实模型调用。
   *
   * <p>没有运行归属就无法确定该用谁的密钥，宁可降级为本地规则摘要，也不能回退到默认连接。
   */
  @Override
  public String summarize(
      String focusQuery, String previousCheckpoint, List<ContextMessage> messages) {
    return "";
  }

  @Override
  public String summarize(
      CompactionScope scope,
      String focusQuery,
      String previousCheckpoint,
      List<ContextMessage> messages) {
    if (!enabled || scope == null || !scope.hasConnection()) return "";
    if (messages == null || messages.isEmpty()) return "";
    try {
      var connection = connections.resolveForExecution(scope.modelConnectionId());
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
        builder.append("上一轮检查点（需增量合并，不要重复列已确认事实）：\n").append(previousCheckpoint).append("\n\n");
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
      var options = GenerateOptions.builder().maxTokens(MAX_TOKENS).maxCompletionTokens(MAX_TOKENS);
      if (officialDeepSeek(connection)) {
        options.additionalBodyParams(Map.of("thinking", Map.of("type", "disabled")));
      }
      ChatResponse response =
          model.stream(request, List.of(), options.build()).blockLast(SUMMARY_TIMEOUT);
      if (response == null) return "";
      // 无论是否拿到用量都要记账：缺失用量记成“未知”，由费用台账按保守口径处理。
      var usage = response.getUsage();
      scope
          .usage()
          .record(
              usage == null ? null : usage.getInputTokens(),
              usage == null ? null : usage.getOutputTokens());
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
