package dev.workbench.infrastructure.model;

import dev.workbench.modelconnection.ModelConnectionService;
import io.agentscope.core.formatter.ResponseFormat;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.GenerateOptions;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** 整理草稿时只向模型发送用户文字，工具列表为空。 */
@Component
public class TaskIdeaModel {
  private final ModelConnectionService connections;

  /** 测试替身可继承并只覆盖两参数方法；生产装配使用带连接服务的构造器。 */
  public TaskIdeaModel() {
    this.connections = null;
  }

  @org.springframework.beans.factory.annotation.Autowired
  public TaskIdeaModel(ModelConnectionService connections) {
    this.connections = connections;
  }

  public String draft(String instructions, String idea) {
    return draft(instructions, idea, null);
  }

  /** 指定用户连接时使用其自带的 Key；未指定时回退本地 DeepSeek 密钥文件（受特权校验约束）。 */
  public String draft(String instructions, String idea, String connectionId) {
    boolean hasConnection = connectionId != null && !connectionId.isBlank();
    if (!hasConnection && connections != null) {
      // 读取宿主机全局密钥属于特权动作：多用户模式下普通用户必须使用自己的连接。
      connections.requireLegacyKeyAccess();
    }
    var model =
        !hasConnection
            ? DeepSeekModelFactory.deepSeekModel()
            : UserModelConnectionFactory.model(
                java.util.Objects.requireNonNull(connections, "未配置用户模型连接服务。")
                    .resolveForExecution(connectionId));
    try {
      var reply =
          model.stream(
                  List.of(
                      Msg.builder().role(MsgRole.SYSTEM).textContent(instructions).build(),
                      Msg.builder().role(MsgRole.USER).textContent(idea).build()),
                  List.of(),
                  GenerateOptions.builder().responseFormat(ResponseFormat.jsonObject()).build())
              .blockLast(java.time.Duration.ofSeconds(90));
      return reply == null
          ? ""
          : reply.getContent().stream()
              .filter(TextBlock.class::isInstance)
              .map(TextBlock.class::cast)
              .map(TextBlock::getText)
              .collect(Collectors.joining());
    } catch (RuntimeException e) {
      throw new IllegalStateException("模型草稿整理失败。可以重试或切换为本地规则整理。");
    }
  }
}
