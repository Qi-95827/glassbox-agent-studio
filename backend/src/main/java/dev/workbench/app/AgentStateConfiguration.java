package dev.workbench.app;

import dev.workbench.persistence.agentscope.JpaAgentStateStore;
import io.agentscope.core.state.AgentStateStore;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/** 使用 JPA 适配保存 AgentScope 会话，继续读写原有 agentscope_sessions 表。 */
@Configuration
public class AgentStateConfiguration {
  @Bean
  AgentStateStore agentStateStore(
      EntityManagerFactory entityManagerFactory, PlatformTransactionManager transactionManager) {
    return new JpaAgentStateStore(entityManagerFactory, transactionManager);
  }
}
