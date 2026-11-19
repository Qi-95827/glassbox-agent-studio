package dev.workbench.external;

import dev.workbench.persistence.entity.ExternalActionEntity;

/** 外部平台对账适配器；实现只能查询，不能代替用户重放写操作。 */
public interface ExternalActionAdapter {
  Probe probe(ExternalActionEntity action);

  enum Outcome {
    SUCCEEDED,
    FAILED,
    NOT_FOUND,
    UNKNOWN
  }

  record Probe(Outcome outcome, String evidence) {}
}
