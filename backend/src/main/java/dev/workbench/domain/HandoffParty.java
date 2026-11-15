package dev.workbench.domain;

import com.fasterxml.jackson.annotation.JsonValue;

/** 当前示例流程中可参与交接的角色，保留既有 JSON 编码。 */
public enum HandoffParty {
  COORDINATOR("coordinator"),
  ANALYSIS_AGENT("analysis-agent"),
  CODING_AGENT("coding-agent"),
  REVIEW_AGENT("review-agent");
  private final String code;

  HandoffParty(String code) {
    this.code = code;
  }

  @JsonValue
  public String code() {
    return code;
  }
}
