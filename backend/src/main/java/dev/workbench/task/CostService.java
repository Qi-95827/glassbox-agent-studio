package dev.workbench.task;

import dev.workbench.domain.ModelKind;
import dev.workbench.domain.ModelMode;
import dev.workbench.domain.StopCause;
import dev.workbench.framework.control.StopExecution;
import dev.workbench.persistence.entity.CostReservationEntity;
import dev.workbench.persistence.entity.ProviderPriceVersionEntity;
import dev.workbench.persistence.entity.RunCostLimitEntity;
import dev.workbench.persistence.repository.CostReservationRepository;
import dev.workbench.persistence.repository.ProviderPriceVersionRepository;
import dev.workbench.persistence.repository.RunCostLimitRepository;
import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import dev.workbench.store.RunRecordService;

/**
 * 费用估算与硬预算。
 *
 * <p>派发模型调用前按价格版本的可计价上界预留；完成后按实际 Token 结算。未知用量不会按零处理，会保留预留并标记人工核对。并发调用通过运行费用行锁串行化。
 */
@Service
public class CostService implements dev.workbench.planning.CandidateBudgetValidator {
  private static final String PROVIDER = "default";
  private final ProviderPriceVersionRepository prices;
  private final RunCostLimitRepository limits;
  private final CostReservationRepository reservations;
  private final TransactionTemplate tx;
  private final TransactionTemplate settlementTx;

  public CostService(
      ProviderPriceVersionRepository prices,
      RunCostLimitRepository limits,
      CostReservationRepository reservations,
      TransactionTemplate tx,
      PlatformTransactionManager transactionManager) {
    this.prices = prices;
    this.limits = limits;
    this.reservations = reservations;
    this.tx = tx;
    this.settlementTx = new TransactionTemplate(transactionManager);
    this.settlementTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  /** 模拟模型默认价格为零，便于没有费用配置时保持原行为。 */
  @PostConstruct
  void ensureMockPrice() {
    if (prices
        .findFirstByProviderAndModelAndActiveTrueOrderByVersionDesc(PROVIDER, ModelKind.MOCK.code())
        .isEmpty()) {
      publish(PROVIDER, ModelKind.MOCK.code(), "CNY", 0, 0, 4096, 4096, "system");
    }
  }

  @Transactional
  public Price publish(
      String provider,
      String model,
      String currency,
      long inputMicrosPerMillion,
      long outputMicrosPerMillion,
      int inputTokenUpperBound,
      int outputTokenUpperBound,
      String publishedBy) {
    if (provider == null || !provider.matches("[A-Za-z0-9._-]{1,60}"))
      throw new IllegalArgumentException("供应方名称无效。");
    if (model == null || !model.matches("[A-Za-z0-9._-]{1,80}"))
      throw new IllegalArgumentException("模型名称无效。");
    if (currency == null || !currency.matches("[A-Z]{2,10}"))
      throw new IllegalArgumentException("币种无效。");
    if (inputMicrosPerMillion < 0
        || outputMicrosPerMillion < 0
        || inputTokenUpperBound < 1
        || outputTokenUpperBound < 1
        || inputTokenUpperBound > 1_000_000
        || outputTokenUpperBound > 1_000_000) throw new IllegalArgumentException("价格或单次上界无效。");
    for (var active : prices.findByProviderAndModelAndActiveTrue(provider, model))
      active.deactivate();
    int version =
        prices.findAllByOrderByProviderAscModelAscVersionDesc().stream()
                .filter(row -> row.getProvider().equals(provider) && row.getModel().equals(model))
                .mapToInt(ProviderPriceVersionEntity::getVersion)
                .max()
                .orElse(0)
            + 1;
    var saved =
        prices.saveAndFlush(
            new ProviderPriceVersionEntity(
                dev.workbench.domain.WorkbenchIds.newId(),
                provider,
                model,
                version,
                currency,
                inputMicrosPerMillion,
                outputMicrosPerMillion,
                inputTokenUpperBound,
                outputTokenUpperBound,
                true,
                Instant.now().toString()));
    return toPrice(saved, publishedBy);
  }

  /** 在任务创建事务内绑定价格版本；没有费用上限时不创建。 */
  @Transactional
  public void bindLimit(String runId, ModelMode mode, Long maxCostMicros) {
    if (maxCostMicros == null) return;
    if (maxCostMicros < 1 || maxCostMicros > 1_000_000_000L)
      throw new IllegalArgumentException("费用硬预算须在 1 至 1000000000 微单位之间。");
    ProviderPriceVersionEntity price = defaultPrice(mode);
    if (price == null)
      throw new IllegalArgumentException("至少配置一个生效价格版本，才能启用费用硬预算。");
    if (limits.existsById(runId)) throw new IllegalStateException("运行已绑定费用预算。");
    String time = Instant.now().toString();
    limits.saveAndFlush(new RunCostLimitEntity(runId, maxCostMicros, price.getId(), time, time));
  }

  /** 恢复子运行沿用父运行价格版本，并把已结算和未结算预留从可用额度中扣除。 */
  @Transactional
  public void inheritLimit(String parentRunId, String childRunId) {
    if (parentRunId == null || childRunId == null || parentRunId.equals(childRunId)) return;
    var parent = limits.findById(parentRunId).orElse(null);
    if (parent == null || limits.existsById(childRunId)) return;
    long remaining =
        Math.max(
            0, parent.getMaxCostMicros() - parent.getSettledMicros() - parent.getReservedMicros());
    String time = Instant.now().toString();
    limits.save(
        new RunCostLimitEntity(childRunId, remaining, parent.getPriceVersionId(), time, time));
  }

  /** 任务级预算只绑定默认价格；每次实际调用仍按 modelId 查表计价。 */
  private ProviderPriceVersionEntity defaultPrice(ModelMode mode) {
    String model = ModelKind.forMode(mode).code();
    var exact = prices.findFirstByProviderAndModelAndActiveTrueOrderByVersionDesc(PROVIDER, model);
    if (exact.isPresent()) return exact.get();
    return prices.findAllByOrderByProviderAscModelAscVersionDesc().stream()
        .filter(ProviderPriceVersionEntity::isActive)
        .findFirst()
        .orElse(null);
  }

  /** 每次 ModelCall 使用实际模型的价格版本，支持同一运行混用不同模型。 */
  private ProviderPriceVersionEntity priceFor(ModelKind model) {
    return prices
        .findFirstByProviderAndModelAndActiveTrueOrderByVersionDesc(PROVIDER, model.code())
        .orElseThrow(
            () ->
                new StopExecution(
                    StopCause.COST_LIMIT,
                    "模型 " + model.code() + " 未配置生效价格版本，无法继续执行费用硬预算。"));
  }

  /**
   * 确认前核对实际模型集合：每个模型都必须有生效价格，且硬预算只能在一个币种内汇总。
   *
   * <p>不同模型可以有不同的输入/输出单价与版本；拒绝的只是缺价和跨币种直接相加。
   */
  @Override
  public void requireBudgetPricing(
      java.util.Collection<ModelKind> models, long maxCostMicros) {
    if (maxCostMicros < 1 || maxCostMicros > 1_000_000_000L)
      throw new IllegalArgumentException("费用硬预算须在 1 到 1000000000 微单位之间。");
    var missing = new java.util.ArrayList<String>();
    var currencies = new java.util.LinkedHashSet<String>();
    for (ModelKind model : models) {
      var price = prices.findFirstByProviderAndModelAndActiveTrueOrderByVersionDesc(
          PROVIDER, model.code());
      if (price.isEmpty()) missing.add(model.code());
      else currencies.add(price.get().getCurrency());
    }
    if (!missing.isEmpty())
      throw new IllegalArgumentException(
          "以下实际模型缺少生效价格，不能启用费用硬预算：" + String.join(", ", missing));
    if (currencies.size() > 1)
      throw new IllegalArgumentException(
          "实际模型价格币种不一致（" + String.join(", ", currencies) + "），不能汇总到同一硬预算。");
  }

  /** 派发前预留可计价上界；额度不足时抛出受控停止，不启动模型调用。 */
  public Reservation reserve(String runId, ModelKind model) {
    return tx.execute(
        ignored -> {
          RunCostLimitEntity limit = limits.lockByRunId(runId).orElse(null);
          if (limit == null) return Reservation.NONE;
          ProviderPriceVersionEntity price = priceFor(model);
          var base = prices.findById(limit.getPriceVersionId()).orElse(null);
          if (base != null && !base.getCurrency().equals(price.getCurrency()))
            throw new StopExecution(
                StopCause.COST_LIMIT,
                "本次模型价格币种 " + price.getCurrency() + " 与运行预算币种 " + base.getCurrency()
                    + " 不一致，已停止派发。");
          long estimate = price.upperBoundMicros();
          long committed = Math.addExact(limit.getReservedMicros(), limit.getSettledMicros());
          if (Math.addExact(committed, estimate) > limit.getMaxCostMicros())
            throw new StopExecution(
                StopCause.COST_LIMIT,
                "费用硬预算不足：已预留 "
                    + limit.getReservedMicros()
                    + "，已结算 "
                    + limit.getSettledMicros()
                    + "，本次上界 "
                    + estimate
                    + "，上限 "
                    + limit.getMaxCostMicros()
                    + " 微单位。");
          String time = Instant.now().toString();
          var row =
              reservations.save(
                  new CostReservationEntity(
                      dev.workbench.domain.WorkbenchIds.newId(),
                      runId,
                      estimate,
                      price.getId(),
                      "RESERVED",
                      time,
                      time));
          limit.reserve(estimate, time);
          limits.save(limit);
          return new Reservation(
              row.getId(),
              estimate,
              price.getInputTokenUpperBound(),
              price.getOutputTokenUpperBound());
        });
  }

  /**
   * 在独立事务中持久化实际用量和超支结果，再返回给调用方停止后续调用。
   *
   * <p>结算不能被外层运行事务回滚；否则账本会丢失已实际发生的费用。
   */
  public Settlement settle(Reservation reservation, Integer input, Integer output) {
    if (reservation == null || reservation.id() == null) return Settlement.none();
    Settlement result =
        settlementTx.execute(
            ignored -> {
              var row = reservations.findById(reservation.id()).orElse(null);
              if (row == null || !"RESERVED".equals(row.getStatus())) return Settlement.none();
              RunCostLimitEntity limit =
                  limits
                      .lockByRunId(row.getRunId())
                      .orElseThrow(() -> new IllegalStateException("费用预算记录缺失。"));
              String priceId =
                  row.getPriceVersionId() == null
                      ? limit.getPriceVersionId()
                      : row.getPriceVersionId();
              ProviderPriceVersionEntity price =
                  prices
                      .findById(priceId)
                      .orElseThrow(() -> new IllegalStateException("价格版本缺失。"));
              boolean unknown = input == null || output == null;
              Long actual = unknown ? null : price.cost(Math.max(0, input), Math.max(0, output));
              String time = Instant.now().toString();
              limit.settle(row.getReservedMicros(), actual == null ? 0 : actual, unknown, time);
              limits.save(limit);
              long overage =
                  unknown ? 0 : Math.max(0, limit.getSettledMicros() - limit.getMaxCostMicros());
              row.settle(actual, unknown, overage, time);
              reservations.save(row);
              return new Settlement(
                  row.getId(),
                  actual,
                  unknown,
                  overage,
                  limit.getSettledMicros(),
                  limit.getMaxCostMicros());
            });
    return result == null ? Settlement.none() : result;
  }

  /** 输入未通过价格上界校验时释放预留，不记为已结算或未知用量。 */
  public void cancel(Reservation reservation) {
    if (reservation == null || reservation.id() == null) return;
    settlementTx.executeWithoutResult(
        ignored -> {
          var row = reservations.findById(reservation.id()).orElse(null);
          if (row == null || !"RESERVED".equals(row.getStatus())) return;
          RunCostLimitEntity limit = limits.lockByRunId(row.getRunId()).orElse(null);
          if (limit != null) {
            limit.release(row.getReservedMicros(), Instant.now().toString());
            limits.save(limit);
          }
          row.cancel(Instant.now().toString());
          reservations.save(row);
        });
  }

  public Map<String, Object> estimate(String runId) {
    var limit = limits.findById(runId).orElse(null);
    if (limit == null) return null;
    var price = prices.findById(limit.getPriceVersionId()).orElse(null);
    var result = new LinkedHashMap<String, Object>();
    result.put("CURRENCY", price == null ? null : price.getCurrency());
    result.put("MAX_COST_MICROS", limit.getMaxCostMicros());
    result.put("RESERVED_MICROS", limit.getReservedMicros());
    result.put("SETTLED_MICROS", limit.getSettledMicros());
    result.put("UNKNOWN_CALLS", limit.getUnknownCalls());
    result.put("OVERAGE_MICROS", limit.getOverageMicros());
    result.put("OVER_BUDGET", limit.getOverageMicros() > 0);
    result.put("PRICE_PROVIDER", price == null ? null : price.getProvider());
    result.put("PRICE_MODEL", price == null ? null : price.getModel());
    result.put("PRICE_VERSION", price == null ? null : price.getVersion());
    result.put("INPUT_MICROS_PER_MILLION", price == null ? null : price.getInputMicrosPerMillion());
    result.put(
        "OUTPUT_MICROS_PER_MILLION", price == null ? null : price.getOutputMicrosPerMillion());
    result.put("PER_CALL_UPPER_BOUND_MICROS", price == null ? null : price.upperBoundMicros());
    result.put("INPUT_TOKEN_UPPER_BOUND", price == null ? null : price.getInputTokenUpperBound());
    result.put("OUTPUT_TOKEN_UPPER_BOUND", price == null ? null : price.getOutputTokenUpperBound());
    result.put("REMAINING_MICROS", limit.getMaxCostMicros() - limit.getSettledMicros());
    result.put(
        "RESERVATIONS",
        reservations.findByRunIdOrderByCreatedAtAsc(runId).stream().map(this::map).toList());
    return result;
  }

  public List<Price> listPrices() {
    return prices.findAllByOrderByProviderAscModelAscVersionDesc().stream()
        .map(row -> toPrice(row, null))
        .toList();
  }

  private Map<String, Object> map(CostReservationEntity row) {
    var price =
        row.getPriceVersionId() == null
            ? null
            : prices.findById(row.getPriceVersionId()).orElse(null);
    Map<String, Object> item = new LinkedHashMap<>();
    item.put("ID", row.getId());
    item.put("RESERVED_MICROS", row.getReservedMicros());
    item.put("SETTLED_MICROS", row.getSettledMicros());
    item.put("OVER_BUDGET_MICROS", row.getOverBudgetMicros());
    item.put("STATUS", row.getStatus());
    item.put("CREATED_AT", row.getCreatedAt());
    item.put("PRICE_VERSION_ID", row.getPriceVersionId());
    item.put("PRICE_MODEL", price == null ? null : price.getModel());
    item.put("CURRENCY", price == null ? null : price.getCurrency());
    item.put("INPUT_MICROS_PER_MILLION", price == null ? null : price.getInputMicrosPerMillion());
    item.put("OUTPUT_MICROS_PER_MILLION", price == null ? null : price.getOutputMicrosPerMillion());
    item.put("PRICE_VERSION", price == null ? null : price.getVersion());
    return item;
  }

  private static Price toPrice(ProviderPriceVersionEntity row, String publishedBy) {
    return new Price(
        row.getId(),
        row.getProvider(),
        row.getModel(),
        row.getVersion(),
        row.getCurrency(),
        row.getInputMicrosPerMillion(),
        row.getOutputMicrosPerMillion(),
        row.getInputTokenUpperBound(),
        row.getOutputTokenUpperBound(),
        row.isActive(),
        row.getCreatedAt(),
        publishedBy);
  }

  public record Reservation(
      String id, long reservedMicros, Integer inputTokenUpperBound, Integer outputTokenUpperBound) {
    public static final Reservation NONE = new Reservation(null, 0, null, null);
  }

  public record Settlement(
      String reservationId,
      Long actualMicros,
      boolean unknown,
      long overBudgetMicros,
      long settledMicros,
      long maxCostMicros) {
    public static final Settlement NONE = new Settlement(null, null, false, 0, 0, 0);

    public static Settlement none() {
      return NONE;
    }
  }

  public record Price(
      String id,
      String provider,
      String model,
      int version,
      String currency,
      long inputMicrosPerMillion,
      long outputMicrosPerMillion,
      int inputTokenUpperBound,
      int outputTokenUpperBound,
      boolean active,
      String createdAt,
      String publishedBy) {}
}
