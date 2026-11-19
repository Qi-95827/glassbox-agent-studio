package dev.workbench.app;

import dev.workbench.persistence.repository.WorkflowCheckpointRepository;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication(scanBasePackages = "dev.workbench")
@EntityScan(basePackages = "dev.workbench.persistence.entity")
@EnableJpaRepositories(basePackageClasses = WorkflowCheckpointRepository.class)
public class WorkbenchApplication {
  public static void main(String[] args) {
    SpringApplication.run(WorkbenchApplication.class, args);
  }
}
