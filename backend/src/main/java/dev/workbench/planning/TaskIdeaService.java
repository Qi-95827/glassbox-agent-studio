package dev.workbench.planning;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.workbench.domain.TaskEntryKind;
import dev.workbench.infrastructure.model.TaskIdeaModel;
import dev.workbench.workspace.WorkspaceService;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/** 将用户想法整理为可审阅草稿，草稿阶段没有持久化或工作区操作。 */
@Service
public class TaskIdeaService {
  private static final Pattern EXISTING =
      Pattern.compile("已有|现有|这个项目|项目里|仓库|代码库|修复|迁移|existing|repository", Pattern.CASE_INSENSITIVE);
  private static final Pattern WEB =
      Pattern.compile("网页|网站|html|web page|浏览器|页面|可视化", Pattern.CASE_INSENSITIVE);
  private static final String INSTRUCTIONS =
      dev.workbench.framework.prompt.PromptResourceLoader.load("planning/task-idea.md");
  private final TaskIdeaModel model;
  private final ObjectMapper json;
  private final dev.workbench.modelconnection.ModelConnectionService connections;

  public TaskIdeaService(
      TaskIdeaModel model,
      ObjectMapper json,
      dev.workbench.modelconnection.ModelConnectionService connections) {
    this.model = model;
    this.json = json;
    this.connections = connections;
  }

  public enum DraftMode {
    LOCAL,
    DEEPSEEK
  }

  public record Draft(
      TaskEntryKind entryKind,
      String goal,
      String deliverable,
      List<String> constraints,
      List<String> acceptanceCriteria,
      List<String> assumptions,
      List<String> questions,
      List<String> suggestedFiles) {}

  public Draft draft(String idea, DraftMode mode) {
    return draft(idea, mode, null);
  }

  public Draft draft(String idea, DraftMode mode, String modelConnectionId) {
    return draft(idea, mode, modelConnectionId, null);
  }

  public Draft draft(String idea, DraftMode mode, String modelConnectionId, String locale) {
    if (idea == null || idea.isBlank() || idea.length() > 2500)
      throw new IllegalArgumentException(
          dev.workbench.framework.i18n.LocalizedMessages.get("taskIdea.invalid"));
    String input = idea.strip();
    boolean english = dev.workbench.framework.prompt.PromptResourceLoader.isEnglish(locale);
    if (mode == null || mode == DraftMode.LOCAL) return local(input, english);
    String connectionId = null;
    if (connections != null) {
      if (modelConnectionId != null && !modelConnectionId.isBlank())
        connectionId = connections.requireEnabledOwned(modelConnectionId);
      // 未显式选择时优先使用当前用户的默认自带 Key 连接；没有才回退本机密钥文件。
      else connectionId = connections.defaultConnectionId().orElse(null);
      // 回退到宿主机全局密钥属于特权动作：多用户模式下普通用户必须使用自己的连接。
      if (connectionId == null) connections.requireLegacyKeyAccess();
    }
    try {
      String instructions =
          locale == null
              ? INSTRUCTIONS
              : dev.workbench.framework.prompt.PromptResourceLoader.load(
                      locale, "planning/task-idea.md")
                  + "\n\n"
                  + dev.workbench.framework.prompt.PromptResourceLoader.outputLanguageDirective(
                      locale);
      Draft draft =
          json.readerFor(Draft.class)
              .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
              .readValue(model.draft(instructions, input, connectionId));
      if (draft == null || draft.entryKind() == null || draft.entryKind() == TaskEntryKind.BUG)
        throw new IllegalArgumentException("任务入口无效。");
      List<String> constraints = items(draft.constraints());
      if (constraints.size() > 9) throw new IllegalArgumentException("约束条目超出范围。");
      List<String> acceptance = items(draft.acceptanceCriteria());
      if (acceptance.isEmpty()) throw new IllegalArgumentException("缺少验收条件。");
      return new Draft(
          draft.entryKind(),
          field(draft.goal(), 500),
          field(draft.deliverable(), 200),
          constraints,
          acceptance,
          items(draft.assumptions()),
          items(draft.questions()),
          draft.entryKind() == TaskEntryKind.CODE
                  && !EXISTING.matcher(input).find()
                  && draft.suggestedFiles() != null
                  && !draft.suggestedFiles().isEmpty()
              ? WorkspaceService.ideaFiles(draft.suggestedFiles())
              : List.of());
    } catch (JsonProcessingException | IllegalArgumentException e) {
      throw new IllegalStateException(
          dev.workbench.framework.i18n.LocalizedMessages.get("taskIdea.format"));
    }
  }

  private Draft local(String input, boolean english) {
    boolean existing = EXISTING.matcher(input).find();
    boolean web = WEB.matcher(input).find();
    boolean design = input.matches("(?s).*(设计方案|概要设计|只做设计|design doc|design only).*");
    boolean requirements =
        input.matches("(?s).*(梳理需求|需求文档|只做需求|requirements doc|requirements only).*");
    boolean test = input.matches("(?s).*(编写测试方案|设计测试|测试用例|test plan|test cases).*");
    TaskEntryKind kind =
        design
            ? TaskEntryKind.DESIGN
            : requirements
                ? TaskEntryKind.REQUIREMENT
                : test
                    ? TaskEntryKind.TEST
                    : web || existing ? TaskEntryKind.CODE : TaskEntryKind.GENERAL;
    List<String> files =
        kind == TaskEntryKind.CODE && web && !existing ? List.of("index.html") : List.of();
    String goal = input.length() <= 500 ? input : input.substring(0, 500).strip();
    List<String> assumptions =
        input.length() > 500
            ? List.of(
                english
                    ? "The local rule used the first 500 characters as the goal. Add any important"
                          + " details beyond that."
                    : "本地规则使用了原文的前 500 字作为目标。请补齐目标之外的重要细节。")
            : files.isEmpty()
                ? List.of()
                : List.of(
                    english
                        ? "The standalone page uses self-contained HTML and opens directly in a"
                              + " browser."
                        : "独立网页采用自包含 HTML，可用浏览器直接打开。");
    List<String> acceptance =
        !files.isEmpty() && (input.contains("冒泡") || input.toLowerCase().contains("bubble"))
            ? List.of(
                english
                    ? "Given 5,1,4,2,8, the page renders five bars with the matching values and"
                          + " shows a message for invalid input."
                    : "输入 5,1,4,2,8，页面显示五根对应数值的柱状条，并提供非法输入提示。",
                english
                    ? "Clicking Next compares pairs; swaps update the bars and mark both positions."
                    : "点击下一步逐对比较；发生交换时更新柱状条并标记两个位置。",
                english
                    ? "Auto-run can pause and resume, ends at the ascending result 1,2,4,5,8, and"
                          + " supports reset."
                    : "自动运行可暂停和继续，最终显示升序结果 1,2,4,5,8；支持重置。")
            : files.isEmpty()
                ? List.of(
                    english
                        ? "Check each task goal item and record the result, supporting evidence and"
                              + " open questions in the delivery notes."
                        : "逐项对照任务目标，在交付说明中给出结果、对应证据和待确认事项。")
                : List.of(
                    english
                        ? "Open index.html and complete the core flow with the sample data."
                        : "打开 index.html，使用示例完成核心操作。",
                    english
                        ? "Show an understandable message when invalid data is entered."
                        : "输入无效数据时显示可理解的提示。");
    return new Draft(
        kind,
        goal,
        files.isEmpty()
            ? kind == TaskEntryKind.CODE
                ? english ? "Modified authorized files and delivery notes" : "修改后的授权文件与交付说明"
                : english ? "Task result and acceptance notes" : "任务结果及验收说明"
            : english ? "index.html that opens in a browser" : "可用浏览器打开的 index.html",
        files.isEmpty()
            ? List.of()
            : List.of(
                english
                    ? "Write the output to the authorized index.html and explain how to open it in"
                          + " the delivery notes."
                    : "把产出写入授权的 index.html，交付说明中提供打开方式。"),
        acceptance,
        assumptions,
        kind == TaskEntryKind.CODE && files.isEmpty()
            ? List.of(english ? "Which existing workspace should be used?" : "本次应使用哪个已有工作区？")
            : List.of(),
        files);
  }

  private static List<String> items(List<String> list) {
    if (list == null) return List.of();
    if (list.size() > 10) throw new IllegalArgumentException("草稿条目超出范围。");
    return list.stream().map(item -> field(item, 300)).distinct().toList();
  }

  private static String field(String value, int maximum) {
    if (value == null || value.isBlank() || value.strip().length() > maximum)
      throw new IllegalArgumentException("草稿字段为空或过长。");
    return value.strip();
  }
}
