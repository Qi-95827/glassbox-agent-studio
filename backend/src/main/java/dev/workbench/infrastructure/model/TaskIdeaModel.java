package dev.workbench.infrastructure.model;

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
  public String draft(String instructions, String idea) {
    var model = DeepSeekModelFactory.deepSeekModel();
    try {
      var reply =
          model.stream(
                  List.of(
                      Msg.builder().role(MsgRole.SYSTEM).textContent(instructions).build(),
                      Msg.builder().role(MsgRole.USER).textContent(idea).build()),
                  List.of(),
                  GenerateOptions.builder().responseFormat(ResponseFormat.jsonObject()).build())
              .blockLast();
      return reply == null
          ? ""
          : reply.getContent().stream()
              .filter(TextBlock.class::isInstance)
              .map(TextBlock.class::cast)
              .map(TextBlock::getText)
              .collect(Collectors.joining());
    } catch (RuntimeException e) {
      throw new IllegalStateException("DeepSeek 草稿整理失败。可以重试或切换为本地规则整理。");
    }
  }
}
