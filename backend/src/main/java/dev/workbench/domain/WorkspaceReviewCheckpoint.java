package dev.workbench.domain;

import java.util.UUID;

/** 只读工作区分析完成后的来源与工作区状态，不包含文件正文。 */
public record WorkspaceReviewCheckpoint(
    UUID workspaceId, String analysisReference, String workspaceFingerprint) {
  public WorkspaceReviewCheckpoint {
    if (workspaceId == null
        || analysisReference == null
        || analysisReference.isBlank()
        || analysisReference.length() > 200
        || workspaceFingerprint == null
        || !workspaceFingerprint.matches("[0-9a-f]{64}"))
      throw new IllegalArgumentException("只读工作区检查点字段无效。");
  }
}
