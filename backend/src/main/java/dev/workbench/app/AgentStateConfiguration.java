package dev.workbench.app;

import io.agentscope.core.state.AgentStateStore;
import io.agentscope.extensions.jdbc.dialect.vendor.H2Dialect;
import io.agentscope.extensions.jdbc.state.JdbcAgentStateStore;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 使用 AgentScope 官方 JDBC 存储，给本机每个 Agent 实例保存独立会话。 */
@Configuration
public class AgentStateConfiguration {
  @Bean
  AgentStateStore agentStateStore(DataSource dataSource) {
    // 使用框架默认前缀 agentscope_，不会覆盖应用自有业务表。
    return new JdbcAgentStateStore(dataSource, new H2Dialect(), true);
  }
}
