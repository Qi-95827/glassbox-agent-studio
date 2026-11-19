package dev.workbench.external;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * 隔离演练适配器：测试和本地演示显式登记外部结果。
 *
 * <p>默认返回未知，避免把“未查询到”误判为失败或成功。生产接入应替换为只读查询真实平台。
 */
@Component
public class InMemoryExternalActionAdapter implements ExternalActionAdapter {
  private final Map<String, Probe> results = new ConcurrentHashMap<>();

  public void register(String actionKey, Outcome outcome, String evidence) {
    results.put(actionKey, new Probe(outcome, evidence));
  }

  public void clear() {
    results.clear();
  }

  @Override
  public Probe probe(dev.workbench.persistence.entity.ExternalActionEntity action) {
    return results.getOrDefault(
        action.getActionKey(), new Probe(Outcome.UNKNOWN, "隔离适配器没有可核对的查询结果。"));
  }
}
