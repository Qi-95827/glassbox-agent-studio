package dev.workbench.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "dev.workbench")
public class WorkbenchApplication {
  public static void main(String[] args) {
    SpringApplication.run(WorkbenchApplication.class, args);
  }
}
