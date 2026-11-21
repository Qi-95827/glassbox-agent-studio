package dev.workbench.framework.model;

/** 项目自有模型客户端契约；具体推理 SDK 由 infrastructure 适配。 */
public interface ModelClient {
  String modelName();

  default boolean supportsNativeStructuredOutput() {
    return false;
  }

  default boolean supportsNativeStructuredOutputWithTools() {
    return false;
  }

  default int contextWindowSize() {
    return 0;
  }
}
