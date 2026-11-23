package dev.workbench.framework.evaluation;

import dev.workbench.framework.context.ContextMessage;
import java.util.List;
import java.util.stream.Collectors;

/** 评测使用的无工具模型调用。缺失的用量和价格保持未知。 */
public interface EvalAnswerModel {
  Session open(String connectionId);

  interface Session {
    String modelName();

    int contextWindowSize();

    boolean pricedInUsd();

    Reply answer(String instructions, String input);

    /** 工具观测通过无工具模型端口传输，并保持为不可信的数据。 */
    default Reply answer(List<ContextMessage> messages, int maxTokens) {
      String instructions = messages.getFirst().content();
      String input =
          messages.stream()
              .skip(1)
              .map(ContextMessage::content)
              .collect(Collectors.joining("\n\n"));
      return answer(instructions, input);
    }

    /** 结构化阶段可要求供应商使用 JSON 对象模式，兼容仅支持普通文本的连接。 */
    default Reply answerObject(List<ContextMessage> messages, int maxTokens) {
      return answer(messages, maxTokens);
    }
  }

  record Reply(String text, Integer inputTokens, Integer outputTokens, Double costUsd) {}
}
