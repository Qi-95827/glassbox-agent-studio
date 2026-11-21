package dev.workbench.framework.model;

/** 项目自有模型客户端契约；具体推理 SDK 由 infrastructure 适配。 */
public interface ModelClient {
  String modelName();

  /**
   * 底层执行引擎句柄；只有对应 infrastructure 适配器能返回可执行对象。
   *
   * <p>framework 不依赖任何 SDK 类型；执行器在装配时校验该句柄，避免到运行期才发现实现不匹配。
   */
  default Object nativeClient() {
    return this;
  }

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
