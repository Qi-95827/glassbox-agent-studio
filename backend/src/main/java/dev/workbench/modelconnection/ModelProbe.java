package dev.workbench.modelconnection;

/** 连通性探测边界；SDK 调用由 infrastructure 实现，本包不依赖推理 SDK。 */
public interface ModelProbe {
  ProbeResult probe(RuntimeModelConnection connection);

  record ProbeResult(boolean ok, String message) {}
}
