package dev.workbench.modelconnection;

import dev.workbench.ownership.OwnershipService;
import dev.workbench.persistence.repository.InvocationRepository;
import dev.workbench.persistence.repository.TaskRepository;
import dev.workbench.persistence.repository.UserModelConnectionRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 自带 Key 运行的参考费用估算。
 *
 * <p>只读取该运行实际记录的 Token 与连接上的参考单价；费用由用户自己的服务商结算，
 * 这里仅用于工作台内预算提示，不参与平台费用硬预算或扣费。
 */
@Service
public class ModelUsageEstimateService {
  private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000);
  private static final String PRICED_LABEL = "用户自备 Key（参考估算）";
  private static final String UNPRICED_LABEL = "未设参考单价（不扣平台费用）";

  private final OwnershipService ownership;
  private final TaskRepository tasks;
  private final UserModelConnectionRepository connections;
  private final InvocationRepository invocations;

  public ModelUsageEstimateService(
      OwnershipService ownership,
      TaskRepository tasks,
      UserModelConnectionRepository connections,
      InvocationRepository invocations) {
    this.ownership = ownership;
    this.tasks = tasks;
    this.connections = connections;
    this.invocations = invocations;
  }

  public record EstimateView(
      boolean byok,
      boolean priced,
      String connectionId,
      String connectionName,
      String modelName,
      String currency,
      long inputTokens,
      long outputTokens,
      int unknownTokenCalls,
      Double inputPricePer1m,
      Double outputPricePer1m,
      BigDecimal estimatedCost,
      String label) {}

  @Transactional(readOnly = true)
  public EstimateView estimate(String runId) {
    if (runId == null || runId.isBlank()) throw new IllegalArgumentException("缺少运行标识。");
    ownership.requireRun(runId);
    String taskId = ownership.taskIdOfRun(runId);
    var task =
        tasks
            .findTaskById(taskId)
            .orElseThrow(() -> new EmptyResultDataAccessException(1));
    long input = 0;
    long output = 0;
    int unknown = 0;
    for (var call : invocations.findModelCalls(runId)) {
      if (call.inputTokens() == null || call.outputTokens() == null) unknown++;
      else {
        input = Math.addExact(input, Math.max(0, call.inputTokens()));
        output = Math.addExact(output, Math.max(0, call.outputTokens()));
      }
    }
    if (task.modelConnectionId() == null || task.modelConnectionId().isBlank())
      return new EstimateView(
          false,
          false,
          null,
          null,
          null,
          null,
          input,
          output,
          unknown,
          null,
          null,
          null,
          "未使用自带 Key 模型");
    var connection = connections.findById(task.modelConnectionId()).orElse(null);
    if (connection == null)
      return new EstimateView(
          true,
          false,
          task.modelConnectionId(),
          null,
          null,
          null,
          input,
          output,
          unknown,
          null,
          null,
          null,
          "模型连接已删除，无法估算");
    Double inputPrice = connection.getInputPricePer1m();
    Double outputPrice = connection.getOutputPricePer1m();
    boolean priced = inputPrice != null || outputPrice != null;
    BigDecimal cost = null;
    if (priced) {
      cost =
          BigDecimal.valueOf(input)
              .multiply(BigDecimal.valueOf(inputPrice == null ? 0 : inputPrice))
              .add(
                  BigDecimal.valueOf(output)
                      .multiply(BigDecimal.valueOf(outputPrice == null ? 0 : outputPrice)))
              .divide(MILLION, 6, RoundingMode.HALF_UP);
    }
    return new EstimateView(
        true,
        priced,
        connection.getId(),
        connection.getDisplayName(),
        connection.getModelName(),
        connection.getCurrency(),
        input,
        output,
        unknown,
        inputPrice,
        outputPrice,
        cost,
        priced ? PRICED_LABEL : UNPRICED_LABEL);
  }
}
