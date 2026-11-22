package dev.workbench.framework.event;

import java.time.Instant;
import java.util.UUID;

/** 不入库的瞬态 SSE 片段：模型增量与安检门实时状态。 */
public record RunStreamTransient(UUID runId, String kind, String text, Instant occurredAt) {
  public RunStreamTransient {
    kind = kind == null || kind.isBlank() ? "MODEL_DELTA" : kind;
    text = text == null ? "" : text;
    occurredAt = occurredAt == null ? Instant.now() : occurredAt;
  }
}
