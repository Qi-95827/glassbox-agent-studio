package dev.workbench.framework.evaluation;

/**
 * 评测模型调用的受控失败。
 *
 * <p>与普通运行时异常不同，这里必须携带稳定错误码，供批次落库与前端抽屉翻译；不得把供应商原始报文
 * 直接暴露给用户。典型场景：DeepSeek 思考模式耗尽输出预算，正文为空（{@code THINKING_TRUNCATED}）。
 */
public class EvalModelException extends RuntimeException {
  private final String code;

  public EvalModelException(String code, String message) {
    super(message == null || message.isBlank() ? code : message);
    if (code == null || code.isBlank()) throw new IllegalArgumentException("错误码不能为空。");
    this.code = code;
  }

  public String code() {
    return code;
  }
}
