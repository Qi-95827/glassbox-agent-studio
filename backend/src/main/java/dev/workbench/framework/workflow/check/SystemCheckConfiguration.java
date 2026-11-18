package dev.workbench.framework.workflow.check;

import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 注册受信系统检查执行器；新增执行器必须在此显式列出并声明唯一版本。 */
@Configuration
class SystemCheckConfiguration {
  @Bean
  SystemCheckRegistry systemCheckRegistry(List<SystemCheckExecutor> executors) {
    return new SystemCheckRegistry(executors);
  }
}
