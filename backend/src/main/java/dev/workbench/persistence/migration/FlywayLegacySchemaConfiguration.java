package dev.workbench.persistence.migration;

import org.springframework.boot.autoconfigure.flyway.FlywayConfigurationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 把旧结构兼容回调注册进 Spring Boot 管理的 Flyway，生产与测试使用同一路径。 */
@Configuration
class FlywayLegacySchemaConfiguration {
  @Bean
  FlywayConfigurationCustomizer legacySchemaColumnCallback() {
    return configuration -> configuration.callbacks(new LegacySchemaColumnCallback());
  }
}
