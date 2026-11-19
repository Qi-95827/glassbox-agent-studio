package dev.workbench;

import jakarta.persistence.EntityManagerFactory;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;

/**
 * 单元测试用最小 JPA 容器。只注册数据源、EntityManagerFactory 和 Repository，
 * 不启动 Web、调度或后台任务；调用方负责准备所需表和关闭容器。
 */
public final class TestJpa implements AutoCloseable {
  private final AnnotationConfigApplicationContext context;

  public TestJpa(DataSource dataSource) {
    context = new AnnotationConfigApplicationContext();
    context.registerBean(DataSource.class, () -> dataSource);
    context.register(Config.class);
    context.refresh();
  }

  public <T> T repository(Class<T> type) {
    return context.getBean(type);
  }

  @Override
  public void close() {
    context.close();
  }

  @TestConfiguration
  @EnableJpaRepositories(basePackages = "dev.workbench.persistence.repository")
  public static class Config {
    @Bean
    LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource dataSource) {
      var factory = new LocalContainerEntityManagerFactoryBean();
      factory.setDataSource(dataSource);
      factory.setPackagesToScan("dev.workbench.persistence.entity");
      factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
      factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "none"));
      return factory;
    }

    @Bean
    JpaTransactionManager transactionManager(EntityManagerFactory entityManagerFactory) {
      return new JpaTransactionManager(entityManagerFactory);
    }
  }
}
