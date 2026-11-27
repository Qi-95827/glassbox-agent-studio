package dev.workbench.timeline;

import dev.workbench.domain.RunSnapshot;
import dev.workbench.domain.TaskDefinition;
import dev.workbench.domain.ToolKind;
import dev.workbench.evaluation.EvalCustomDatasetService;
import dev.workbench.framework.trace.SensitiveDataMasker;
import dev.workbench.store.AgentAssemblyRecordService;
import dev.workbench.store.RunRecordService;
import dev.workbench.store.TaskRecordService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;

/**
 * Eval Flywheel：把一次真实运行脱敏沉淀为 Eval Lab 自定义数据集用例。
 *
 * <p>只写既有自定义数据集口径（{@link EvalCustomDatasetService#upsertSample}），不引入新的评分规则，
 * 也不改动已发布的评测用例：断言只用已经存在的两种类型（运行状态、输出包含），值来自这次运行的真实
 * 证据，且全部经过脱敏与截断。
 */
@Service
public class EvalFlywheelService {
  static final String DEFAULT_DATASET_CODE = "run-distilled";
  static final String DEFAULT_DATASET_NAME = "从运行沉淀的用例";
  static final String DEFAULT_DATASET_CATEGORY =
      dev.workbench.framework.evaluation.EvalCategory.CODING_REFLECTION.name();
  private static final int MAX_PROMPT = 4000;
  private static final int MAX_OUTPUT_NEEDLE = 120;

  private final RunRecordService records;
  private final TaskRecordService tasks;
  private final AgentAssemblyRecordService agents;
  private final EvalCustomDatasetService datasets;

  public EvalFlywheelService(
      RunRecordService records,
      TaskRecordService tasks,
      AgentAssemblyRecordService agents,
      EvalCustomDatasetService datasets) {
    this.records = records;
    this.tasks = tasks;
    this.agents = agents;
    this.datasets = datasets;
  }

  /** 脱敏后的用例草稿：预览与落库共用同一份构造，保证「看到的就是写进去的」。 */
  public record CaseDraft(
      String datasetId,
      String datasetCode,
      String sampleKey,
      String titleZh,
      String titleEn,
      String prompt,
      Map<String, Object> setup,
      List<Map<String, Object>> assertions,
      List<String> limitations) {}

  public record Exported(String datasetId, String datasetCode, String sampleKey, String total) {}

  /** 预览：严格只读不写库，供前端弹窗展示将要落库的内容。 */
  public CaseDraft preview(String runId) {
    var existing = findExistingDataset();
    return buildDraft(
        runId,
        existing.map(EvalCustomDatasetService.DatasetView::id).orElse(null),
        existing.map(EvalCustomDatasetService.DatasetView::code).orElse(DEFAULT_DATASET_CODE));
  }

  /** 落库：按需创建或复用默认沉淀数据集，并写入（或覆盖）同一样本键的用例。 */
  public Exported export(String runId) {
    var dataset = requireDataset();
    var draft = buildDraft(runId, dataset.id(), dataset.code());
    datasets.upsertSample(
        draft.datasetId(),
        draft.sampleKey(),
        new EvalCustomDatasetService.SampleInput(
            draft.sampleKey(),
            draft.titleZh(),
            draft.titleEn(),
            draft.prompt(),
            draft.setup(),
            draft.assertions(),
            List.of()));
    int total = datasets.samples(draft.datasetId()).size();
    return new Exported(draft.datasetId(), draft.datasetCode(), draft.sampleKey(), total + " 题");
  }

  private CaseDraft buildDraft(String runId, String datasetId, String datasetCode) {
    RunSnapshot snapshot = records.runSnapshot(runId);
    TaskDefinition task = tasks.taskDefinition(snapshot.taskId().toString());
    String status = snapshot.status().name();
    String output = finalOutput(runId);
    var assertions = new ArrayList<Map<String, Object>>();
    assertions.add(new LinkedHashMap<>(Map.of("type", "RUN_STATUS_MATCH", "expected", status)));
    if (!snapshot.status().canCancel())
      assertions.add(new LinkedHashMap<>(Map.of("type", "RUN_TERMINAL", "expected", true)));
    // 默认沉淀集属于 CODING_REFLECTION，由 CodingReflectionRunner 执行，其 rawOutput 始终是工作区目标文件正文，
    // 而不是上游 Agent 的对话叙述；无论是否发生写文件，把对话正文塞进 OUTPUT_CONTAINS 都会在重跑时与文件内容错配。
    // 因此此处只沉淀可稳定核对的运行状态与终态断言，并将脱敏输出摘要写入 setup 供人工对照。
    boolean workspaceWrite = hasSuccessfulWorkspaceWrite(runId);
    var setup = new LinkedHashMap<String, Object>();
    setup.put("sourceRunId", runId);
    setup.put("sourceTaskId", snapshot.taskId().toString());
    setup.put("sourceStatus", status);
    setup.put("source", "RUN_DISTILLED");
    setup.put("completedNodes", completedNodes(runId));
    setup.put("workspaceWrite", workspaceWrite);
    if (output != null && !output.isBlank()) {
      String needle = output.strip();
      if (needle.length() > MAX_OUTPUT_NEEDLE) needle = needle.substring(0, MAX_OUTPUT_NEEDLE);
      setup.put("sourceOutputPreview", mask(needle));
    }
    var limitations = new ArrayList<String>();
    limitations.add("用例只沉淀提示词与既有断言的期望值；模型原始回复、工具返回值与工作区文件不会进入评测数据集。");
    if (workspaceWrite)
      limitations.add(
          "该运行含工作区写入：产物是授权文件正文，未落入本用例；因此只断言运行状态与终态，不用上游叙述文字去匹配代码文件。");
    else if (output != null && !output.isBlank())
      limitations.add(
          "默认沉淀数据集属于 CODING_REFLECTION，重跑时以工作区目标文件正文为 rawOutput；因此只断言运行状态与终态，不将上游 Agent 叙述作为 OUTPUT_CONTAINS（脱敏摘要已保存在 setup.sourceOutputPreview）。");
    if (output == null) limitations.add("这次运行没有保存最终输出，因此只沉淀运行终态断言。");
    return new CaseDraft(
        datasetId,
        datasetCode,
        sampleKey(runId),
        "运行沉淀： " + shortId(runId),
        "Distilled from run " + shortId(runId),
        mask(goal(task)),
        setup,
        List.copyOf(assertions),
        List.copyOf(limitations));
  }

  private java.util.Optional<EvalCustomDatasetService.DatasetView> findExistingDataset() {
    return datasets.list().stream()
        .filter(view -> DEFAULT_DATASET_CODE.equals(view.code()))
        .findFirst();
  }

  /** 复用既有自定义数据集口径：同 code 复用，缺失才创建。 */
  private EvalCustomDatasetService.DatasetView requireDataset() {
    return findExistingDataset()
        .orElseGet(
            () ->
                datasets.create(
                    new EvalCustomDatasetService.DatasetInput(
                        DEFAULT_DATASET_CODE,
                        DEFAULT_DATASET_NAME,
                        DEFAULT_DATASET_CATEGORY,
                        "从真实运行一键脱敏沉淀的回归用例，评分规则与既有自定义数据集一致。")));
  }

  private String finalOutput(String runId) {
    for (var row : agents.agents(runId)) {
      Object value = row.get("RESULT");
      if (value == null) continue;
      String text = String.valueOf(value);
      if (!text.isBlank()) return text;
    }
    return null;
  }

  /** 是否真的发生过成功的写工具调用；匹配真实落库名（ToolKind.WORKSPACE_WRITE.recordName()）及英文工具名。 */
  private boolean hasSuccessfulWorkspaceWrite(String runId) {
    var rows = agents.tools(runId);
    if (rows == null) return false;
    for (var row : rows) {
      String rawName =
          row.get("TOOL_NAME") == null ? "" : String.valueOf(row.get("TOOL_NAME")).strip();
      String status = row.get("STATUS") == null ? "" : String.valueOf(row.get("STATUS"));
      if (!("COMPLETED".equals(status) || "SUCCEEDED".equals(status))) continue;
      if (ToolKind.WORKSPACE_WRITE.recordName().equals(rawName)
          || ToolKind.WORKSPACE_WRITE.name().equalsIgnoreCase(rawName)
          || rawName.toLowerCase(java.util.Locale.ROOT).contains("write")) return true;
    }
    return false;
  }

  private int completedNodes(String runId) {
    return (int)
        Objects.requireNonNull(agents.agents(runId)).stream()
            .filter(row -> row.get("RESULT") != null)
            .count();
  }

  private static String goal(TaskDefinition task) {
    String goal = task == null ? null : task.goal();
    return goal == null || goal.isBlank() ? "（未记录任务目标）" : goal;
  }

  private static String sampleKey(String runId) {
    return "run-" + shortId(runId);
  }

  private static String shortId(String runId) {
    return runId.length() <= 12 ? runId : runId.substring(0, 12);
  }

  private static String mask(String value) {
    return SensitiveDataMasker.maskAndTruncate(value, MAX_PROMPT).text();
  }
}
