package dev.workbench.framework.workflow.check;

import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * 授权读取证据检查 v1：确认直接前置节点存在已结算的授权文件读取或搜索调用。
 *
 * <p>该检查用于只读工作区流程的人工确认之后，防止下游汇总在没有实际读取证据时把分析写成已观察事实。 它只核对交接中已记录的工具调用引用，不重新读取文件，因此不产生新的外部副作用。
 */
@Component
public class AuthorizedReadEvidenceCheck implements SystemCheckExecutor {
  public static final SystemCheckRef REF = new SystemCheckRef("authorized-read-evidence", 1);

  @Override
  public SystemCheckRef ref() {
    return REF;
  }

  @Override
  public SystemCheckResult run(SystemCheckRequest request) {
    if (request.inputs().isEmpty())
      return SystemCheckResult.blocked("检查节点没有可核对的前置结果。", "缺少直接前置节点。");
    var unsettled =
        request.inputs().stream()
            .filter(input -> !input.settled())
            .map(SystemCheckInput::key)
            .toList();
    if (!unsettled.isEmpty())
      return SystemCheckResult.blocked(
          "前置节点尚未全部结算，不能确认读取证据。", "未结算节点：" + String.join(",", unsettled));
    if (request.inputs().stream().anyMatch(input -> input.result().isBlank()))
      return SystemCheckResult.blocked("前置结果为空，不能确认读取证据。", "存在空白结果。");
    var withEvidence = request.inputs().stream().filter(SystemCheckInput::hasReadEvidence).toList();
    if (withEvidence.isEmpty())
      return SystemCheckResult.failed("未发现授权文件读取或搜索的实际调用记录，下游分析缺少可核对证据。", "无已完成读取证据的相邻前置结果。");
    String evidence =
        withEvidence.stream()
            .map(
                input ->
                    input.key()
                        + "="
                        + input.toolEvidence().stream()
                            .map(item -> item.tool() + ":" + item.toolCallId())
                            .collect(Collectors.joining(";")))
            .collect(Collectors.joining("|"));
    if (evidence.length() > 2000) evidence = evidence.substring(0, 1999) + "…";
    int calls = withEvidence.stream().mapToInt(input -> input.toolEvidence().size()).sum();
    return SystemCheckResult.passed(
        "已核对 " + withEvidence.size() + " 个前置节点的 " + calls + " 条授权文件读取或搜索调用，读取证据成立。");
  }
}
