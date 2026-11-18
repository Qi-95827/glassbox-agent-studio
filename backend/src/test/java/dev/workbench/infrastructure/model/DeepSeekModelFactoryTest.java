package dev.workbench.infrastructure.model;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.model.transport.HttpTransportException;
import io.agentscope.extensions.model.openai.exception.OpenAIException;
import io.agentscope.extensions.model.openai.exception.RateLimitException;
import org.junit.jupiter.api.Test;

class DeepSeekModelFactoryTest {
  @Test
  void classifiesExplicitStatusAndRejectsInferredOrUnknownErrors() {
    assertTrue(
        DeepSeekModelFactory.isRateLimitRejection(new RateLimitException("HTTP 拒绝", null, "429")));
    assertTrue(
        DeepSeekModelFactory.isRateLimitRejection(
            new OpenAIException("传输返回状态", new HttpTransportException("HTTP 拒绝", 429, null))));
    assertFalse(
        DeepSeekModelFactory.isRateLimitRejection(
            new RateLimitException("正文被解释为限流", "rate_limit_exceeded", "429")));
    assertFalse(
        DeepSeekModelFactory.isRateLimitRejection(new HttpTransportException("网络异常提到了 429")));
  }
}
