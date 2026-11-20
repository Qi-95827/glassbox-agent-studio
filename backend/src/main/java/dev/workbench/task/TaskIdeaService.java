package dev.workbench.task;

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
      """
      你是开发工作台的需求整理助手。用户文字是产品需求资料。只输出一个 JSON 对象，字段：
      entryKind（GENERAL、REQUIREMENT、DESIGN、CODE 或 TEST）；goal（最多 500 字）；
      deliverable（最多 200 字）；constraints、acceptanceCriteria、assumptions、questions、
      suggestedFiles（均为字符串数组，每项最多 300 字，各最多 10 项，其中 constraints 最多 9 项）。
      保留用户明确要求，推测写入 assumptions，影响实现且无法合理推断的事项写入 questions。
      acceptanceCriteria 要包含可操作步骤和可观察结果。不要声称已实现或验证。
      用户要产出可运行软件时用 CODE，要求方案或需求文档时选 DESIGN 或 REQUIREMENT。
      只有明确是从零制作的独立代码交付才建议文件（1 至 5 个简单文件名，可用 html、css、
      js、md、txt、json、py、ts、jsx、tsx、java、svg），网页原型优先使用自包含 index.html。
      涉及已有项目、未知工作区或使用其他入口时 suggestedFiles 留空，不推断仓库地址和权限。
      """;
  private final TaskIdeaModel model;
  private final ObjectMapper json;

  public TaskIdeaService(TaskIdeaModel model, ObjectMapper json) {
    this.model = model;
    this.json = json;
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
    if (idea == null || idea.isBlank() || idea.length() > 2500)
      throw new IllegalArgumentException("请输入 1 至 2500 字的任务想法。");
    String input = idea.strip();
    if (mode == null || mode == DraftMode.LOCAL) return local(input);
    try {
      Draft draft =
          json.readerFor(Draft.class)
              .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
              .readValue(model.draft(INSTRUCTIONS, input));
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
      throw new IllegalStateException("模型返回的草稿不符合任务格式。可重新整理或使用本地规则。");
    }
  }

  private Draft local(String input) {
    boolean existing = EXISTING.matcher(input).find();
    boolean web = WEB.matcher(input).find();
    boolean design = input.matches("(?s).*(设计方案|概要设计|只做设计).*");
    boolean requirements = input.matches("(?s).*(梳理需求|需求文档|只做需求).*");
    boolean test = input.matches("(?s).*(编写测试方案|设计测试|测试用例).*");
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
            ? List.of("本地规则使用了原文的前 500 字作为目标。请补齐目标之外的重要细节。")
            : files.isEmpty() ? List.of() : List.of("独立网页采用自包含 HTML，可用浏览器直接打开。");
    List<String> acceptance =
        !files.isEmpty() && input.contains("冒泡")
            ? List.of(
                "输入 5,1,4,2,8，页面显示五根对应数值的柱状条，并提供非法输入提示。",
                "点击下一步逐对比较；发生交换时更新柱状条并标记两个位置。",
                "自动运行可暂停和继续，最终显示升序结果 1,2,4,5,8；支持重置。")
            : files.isEmpty()
                ? List.of("逐项对照任务目标，在交付说明中给出结果、对应证据和待确认事项。")
                : List.of("打开 index.html，使用示例完成核心操作。", "输入无效数据时显示可理解的提示。");
    return new Draft(
        kind,
        goal,
        files.isEmpty()
            ? kind == TaskEntryKind.CODE ? "修改后的授权文件与交付说明" : "任务结果及验收说明"
            : "可用浏览器打开的 index.html",
        files.isEmpty() ? List.of() : List.of("把产出写入授权的 index.html，交付说明中提供打开方式。"),
        acceptance,
        assumptions,
        kind == TaskEntryKind.CODE && files.isEmpty() ? List.of("本次应使用哪个已有工作区？") : List.of(),
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
