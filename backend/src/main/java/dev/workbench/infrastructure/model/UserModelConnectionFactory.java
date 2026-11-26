package dev.workbench.infrastructure.model;

import dev.workbench.modelconnection.ModelProviderType;
import dev.workbench.modelconnection.RuntimeModelConnection;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.extensions.model.openai.compat.deepseek.DeepSeekFormatter;
import io.agentscope.extensions.model.openai.formatter.OpenAIChatFormatter;
import java.time.Duration;

/** 按用户连接构造 OpenAI 兼容模型；DeepSeek 使用专用 Formatter，其余使用标准 OpenAI 协议。 */
final class UserModelConnectionFactory {
  /** 建连超时；读取/生成超时仍由 AgentScope 的执行配置控制。 */
  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(20);

  private UserModelConnectionFactory() {}

  static Model model(RuntimeModelConnection connection) {
    return model(connection, false);
  }

  /**
   * 按用户连接构造 OpenAI 兼容模型。
   *
   * @param streaming 是否开启 SSE 增量。只有动态工作流的 Agent 主循环需要流式：那里的每个
   *     增量都会实时推给前端；规划、摘要与评测调用只在结束时取完整结果，开流式既无收益，
   *     还会让只支持整包返回的兼容网关失效。
   */
  static Model model(RuntimeModelConnection connection, boolean streaming) {
    if (connection == null) throw new IllegalStateException("缺少用户模型连接配置。");
    var builder =
        OpenAIChatModel.builder()
            .apiKey(connection.apiKey())
            .baseUrl(connection.baseUrl())
            .modelName(connection.modelName())
            // 未设置窗口时 SDK 返回 0，生产链路会永久跳过四级压缩。
            .contextWindowSize(ModelContextWindows.forModel(connection.modelName()))
            .stream(streaming)
            // 传输层禁止 302 自动跟随，防止已校验的公网 Base URL 被重定向到内网地址。
            .httpTransport(ModelHttpTransports.hardened(CONNECT_TIMEOUT))
            .generateOptions(GenerateOptions.builder().parallelToolCalls(false).build());
    if (connection.providerType() == ModelProviderType.DEEPSEEK)
      builder.formatter(new DeepSeekFormatter());
    else builder.formatter(new OpenAIChatFormatter());
    return builder.build();
  }
}
