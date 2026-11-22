package dev.workbench.infrastructure.model;

import dev.workbench.framework.planning.PlanCandidateModel;
import dev.workbench.modelconnection.ModelConnectionService;
import io.agentscope.core.formatter.ResponseFormat;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.GenerateOptions;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** 使用真实模型生成规划候选；工具列表为空，模型不能执行任何操作。 */
@Component
@ConditionalOnProperty(
    name = "workbench.planning.generator",
    havingValue = "deepseek",
    matchIfMissing = true)
public class DeepSeekPlanCandidateModel implements PlanCandidateModel {
  private final ModelConnectionService connections;

  public DeepSeekPlanCandidateModel(ModelConnectionService connections) {
    this.connections = connections;
  }

  @Override
  public Reply propose(String instructions, String input) {
    return propose(instructions, input, null);
  }

  @Override
  public Reply propose(String instructions, String input, String modelConnectionId) {
    var connection =
        modelConnectionId == null || modelConnectionId.isBlank()
            ? null
            : connections.resolveForExecution(modelConnectionId);
    var model =
        connection == null
            ? DeepSeekModelFactory.deepSeekModel()
            : UserModelConnectionFactory.model(connection);
    String modelName = connection == null ? "deepseek-flash" : connection.modelName();
    try {
      var reply =
          model.stream(
                  List.of(
                      Msg.builder().role(MsgRole.SYSTEM).textContent(instructions).build(),
                      Msg.builder().role(MsgRole.USER).textContent(input).build()),
                  List.of(),
                  GenerateOptions.builder().responseFormat(ResponseFormat.jsonObject()).build())
              .blockLast();
      String text =
          reply == null
              ? ""
              : reply.getContent().stream()
                  .filter(TextBlock.class::isInstance)
                  .map(TextBlock.class::cast)
                  .map(TextBlock::getText)
                  .collect(Collectors.joining());
      // AgentScope 当前适配没有向本服务回传用量；保持未知，不写 0。
      return new Reply(text, modelName, null, null);
    } catch (RuntimeException e) {
      throw new IllegalStateException("规划请求失败。可以重试或改用固定模板。");
    }
  }
}
