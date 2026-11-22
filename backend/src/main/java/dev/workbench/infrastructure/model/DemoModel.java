package dev.workbench.infrastructure.model;

import dev.workbench.domain.AgentRole;
import dev.workbench.domain.Scenario;
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

/** 确定性测试模型也使用框架消息和工具调用协议，而非手写执行循环。 */
public final class DemoModel implements Model {
  private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
      new com.fasterxml.jackson.databind.ObjectMapper();
  private final AtomicInteger calls = new AtomicInteger();
  private final AgentRole role;
  private final boolean workspaceAvailable;
  private final Scenario scenario;
  private final List<String> fileScopes;
  private final boolean textOnly;
  private final boolean workflowInput;
  private final String locale;

  public DemoModel(
      AgentRole role, boolean workspaceAvailable, Scenario scenario, List<String> fileScopes) {
    this(role, workspaceAvailable, scenario, fileScopes, false, false, null);
  }

  /** 按任务语言返回对应语言的演示分析与交接文本。 */
  public DemoModel(
      AgentRole role,
      boolean workspaceAvailable,
      Scenario scenario,
      List<String> fileScopes,
      String locale) {
    this(role, workspaceAvailable, scenario, fileScopes, false, false, locale);
  }

  DemoModel(
      AgentRole role,
      boolean workspaceAvailable,
      Scenario scenario,
      List<String> fileScopes,
      boolean textOnly,
      boolean workflowInput) {
    this(role, workspaceAvailable, scenario, fileScopes, textOnly, workflowInput, null);
  }

  DemoModel(
      AgentRole role,
      boolean workspaceAvailable,
      Scenario scenario,
      List<String> fileScopes,
      boolean textOnly,
      boolean workflowInput,
      String locale) {
    this.role = role;
    this.workspaceAvailable = workspaceAvailable;
    this.scenario = scenario;
    this.fileScopes = List.copyOf(fileScopes);
    this.textOnly = textOnly;
    this.workflowInput = workflowInput;
    this.locale = dev.workbench.framework.prompt.PromptResourceLoader.normalizeLocale(locale);
  }

  private boolean english() {
    return dev.workbench.framework.prompt.PromptResourceLoader.isEnglish(locale);
  }

  public String getModelName() {
    return "mock";
  }

  public Flux<ChatResponse> stream(
      List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
    if (textOnly) {
      if (!tools.isEmpty()) return Flux.error(new IllegalStateException("纯文本 Agent 不应获得工具。"));
      if (role == AgentRole.REVIEWER) {
        String allText = textOf(messages);
        boolean reflectionTeaching =
            allText.contains("反思")
                || allText.toLowerCase(java.util.Locale.ROOT).contains("reflection");
        if (reflectionTeaching) {
          boolean revised = allText.contains("REVISION_APPLIED") || allText.contains("修订已应用");
          String verdict =
              revised
                  ? english()
                      ? "DECISION: APPROVED\n```json\n{\"decision\":\"APPROVED\",\"summary\":\"Revision accepted.\",\"defects\":[]}\n```\nReview complete."
                      : "DECISION: APPROVED\n```json\n{\"decision\":\"APPROVED\",\"summary\":\"修订通过。\",\"defects\":[]}\n```\n审查完成。"
                  : english()
                      ? "DECISION: REVISE_REQUIRED\n```json\n{\"decision\":\"REVISE_REQUIRED\",\"summary\":\"The analysis needs one revision.\",\"defects\":[{\"severity\":\"MAJOR\",\"filePath\":null,\"summary\":\"Missing concrete revision marker.\",\"suggestedFix\":\"Apply the critic feedback and mark REVISION_APPLIED.\"}]}\n```\nReview complete."
                      : "DECISION: REVISE_REQUIRED\n```json\n{\"decision\":\"REVISE_REQUIRED\",\"summary\":\"分析需要修订一轮。\",\"defects\":[{\"severity\":\"MAJOR\",\"filePath\":null,\"summary\":\"缺少明确的修订标记。\",\"suggestedFix\":\"应用批评反馈并标记 REVISION_APPLIED。\"}]}\n```\n审查完成。";
          return Flux.just(
              ChatResponse.builder()
                  .content(List.of(TextBlock.builder().text(verdict).build()))
                  .build());
        }
      }
      return Flux.just(
          ChatResponse.builder()
              .content(
                  List.of(
                      TextBlock.builder()
                          .text(
                              (english()
                                      ? "Demo text analysis returned. Example: verify the task goal, applicable constraints and checkable acceptance criteria."
                                      : "模拟文本分析已返回。示例：核对任务目标、适用约束和可验证的验收条件。")
                                  + (textOf(messages).contains("criticFeedback")
                                          && textOf(messages).contains("REVISE_REQUIRED")
                                      ? english()
                                          ? "\nREVISION_APPLIED: addressed the critic feedback."
                                          : "\n修订已应用（REVISION_APPLIED）：已按批评反馈修订。"
                                      : ""))
                          .build()))
              .build());
    }
    if (role == AgentRole.REVIEWER) {
      String allText = textOf(messages);
      boolean received =
          allText.contains("analysis-agent")
              || allText.contains("coding-agent")
              || allText.contains("evidenceEventIds");
      if (!received) return Flux.error(new IllegalStateException("审查 Agent 未收到结构化交接。"));
      if (!tools.isEmpty()) return Flux.error(new IllegalStateException("审查 Agent 不应获得工具。"));
      boolean reflectionTeaching =
          allText.contains("反思") || allText.toLowerCase(java.util.Locale.ROOT).contains("reflection");
      boolean revised = allText.contains("REVISION_APPLIED") || allText.contains("修订已应用");
      String verdict =
          reflectionTeaching && !revised
              ? english()
                  ? "DECISION: REVISE_REQUIRED\n```json\n{\"decision\":\"REVISE_REQUIRED\",\"summary\":\"The implementation is not ready for acceptance.\",\"defects\":[{\"severity\":\"MAJOR\",\"filePath\":null,\"summary\":\"Acceptance evidence is missing.\",\"suggestedFix\":\"Add a concrete verification result and mark REVISION_APPLIED.\"}]}\n```\nReview complete: provide concrete verification evidence before acceptance."
                  : "DECISION: REVISE_REQUIRED\n```json\n{\"decision\":\"REVISE_REQUIRED\",\"summary\":\"当前实现还不能验收。\",\"defects\":[{\"severity\":\"MAJOR\",\"filePath\":null,\"summary\":\"缺少可核对的验收证据。\",\"suggestedFix\":\"补充具体验证结果，并在交接正文标记 REVISION_APPLIED。\"}]}\n```\n审查完成：请先补充可核对证据再验收。"
              : english()
                  ? "DECISION: APPROVED\n```json\n{\"decision\":\"APPROVED\",\"summary\":\"Review passed with the provided evidence.\",\"defects\":[]}\n```\nReview complete: upstream tool output is simulated data and does not prove real code changes or passing tests. Obtain real evidence and verify further."
                  : "DECISION: APPROVED\n```json\n{\"decision\":\"APPROVED\",\"summary\":\"基于现有证据通过审查。\",\"defects\":[]}\n```\n审查完成：上游工具结果为模拟数据，不证明真实代码已修改或测试通过；应进一步获取真实证据并验证。";
      return Flux.just(
          ChatResponse.builder()
              .content(List.of(TextBlock.builder().text(verdict).build()))
              .build());
    }
    if (role == AgentRole.CODER) {
      boolean received =
          messages.stream()
              .map(Msg::getTextContent)
              .filter(java.util.Objects::nonNull)
              .anyMatch(
                  text ->
                      workflowInput
                          ? containsWorkflowDependency(text)
                          : text.contains("analysis-agent") && text.contains("evidenceEventIds"));
      if (!received) return Flux.error(new IllegalStateException("开发 Agent 未收到分析交接。"));
    }
    int callIndex = calls.getAndIncrement();
    if (callIndex == 0
        || (scenario == Scenario.REPEAT && role == AgentRole.ANALYST && callIndex < 4)) {
      String privateSearch =
          tools.stream()
              .map(schema -> schema.getName())
              .filter(name -> name != null && name.startsWith("search_private_"))
              .findFirst()
              .orElse(null);
      Map<String, Object> input;
      String toolName;
      if (privateSearch != null) {
        // 私有搜索工具使用固定查询参数；实际范围仍由服务端授权集合与扩展名过滤决定。
        toolName = privateSearch;
        input = Map.of("query", fileScopes.isEmpty() ? "a" : "a");
      } else {
        input =
            workspaceAvailable && fileScopes.size() > 1
                ? Map.of("path", fileScopes.getFirst())
                : Map.of();
        toolName = workspaceAvailable ? "read_authorized_file" : "inspect_simulated_issue";
      }
      String content;
      try {
        // AgentScope 按原始 JSON 验证有参数的工具调用，模拟消息也必须提供一致的内容。
        content = JSON.writeValueAsString(input);
      } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
        return Flux.error(new IllegalStateException("无法构造模拟工具参数。", e));
      }
      return Flux.just(
          ChatResponse.builder()
              .content(
                  List.of(
                      ToolUseBlock.builder()
                          .id("mock-tool-" + (callIndex + 1))
                          .name(toolName)
                          .input(input)
                          .content(content)
                          .build()))
              .build());
    }
    var results =
        messages.stream()
            .flatMap(message -> message.getContentBlocks(ToolResultBlock.class).stream())
            .toList();
    if (results.isEmpty() || !isSuccessfulToolResult(results.getLast()))
      return Flux.error(new IllegalStateException("模拟模型未收到成功的工具结果。"));
    String allText = textOf(messages);
    boolean revision = allText.contains("REVISE_REQUIRED") && allText.contains("criticFeedback");
    return Flux.just(
        ChatResponse.builder()
            .content(
                List.of(
                    TextBlock.builder()
                        .text(
                            english()
                                ? role == AgentRole.CODER
                                    ? "Summary: the demo coding agent read authorized files and made no real code change. Decision: keep the implementation and verification steps separate. Risk: no real test result yet."
                                        + (revision
                                            ? "\nREVISION_APPLIED: addressed the critic feedback and added a concrete verification note."
                                            : "")
                                    : "Summary: demo analysis complete. Decision: verify inputs, expected behavior and regression coverage. Risk: no real test evidence has been produced."
                                        + (revision
                                            ? "\nREVISION_APPLIED: addressed the critic feedback in the revised analysis."
                                            : "")
                                : role == AgentRole.CODER
                                    ? "模拟开发 Agent 已读取授权文件，本次未执行代码修改；仍需真实实现与验证。"
                                        + (revision
                                            ? "\n修订已应用（REVISION_APPLIED）：已处理批评反馈并补充可核对说明。"
                                            : "")
                                    : "模拟分析完成：建议核对输入、预期行为并准备回归验证。尚无真实测试结果。"
                                        + (revision
                                            ? "\n修订已应用（REVISION_APPLIED）：已按批评反馈补充分析。"
                                            : ""))
                        .build()))
            .build());
  }

  private static String textOf(List<Msg> messages) {
    StringBuilder text = new StringBuilder();
    for (var message : messages) {
      if (message.getTextContent() != null) text.append(message.getTextContent()).append('\n');
    }
    return text.toString();
  }

  private static boolean containsWorkflowDependency(String text) {
    try {
      var input = JSON.readTree(text);
      return input != null
          && input.path("dependencies").isArray()
          && !input.path("dependencies").isEmpty();
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      return false;
    }
  }

  /** 测试模型只能在工具包装结果明确成功时继续，不能把框架错误消息当成执行证据。 */
  private static boolean isSuccessfulToolResult(ToolResultBlock result) {
    if (result.getState() == io.agentscope.core.message.ToolResultState.ERROR) return false;
    for (var block : result.getOutput()) {
      if (block instanceof TextBlock text) {
        if (text.getText() == null) continue;
        try {
          var value = JSON.readTree(text.getText());
          if (value != null && value.isTextual()) value = JSON.readTree(value.asText());
          if (value != null && "SUCCEEDED".equals(value.path("status").asText())) return true;
        } catch (com.fasterxml.jackson.core.JsonProcessingException ignored) {
          // 非本项目工具的普通文本不构成已验证的成功回执。
        }
      }
    }
    return false;
  }
}
