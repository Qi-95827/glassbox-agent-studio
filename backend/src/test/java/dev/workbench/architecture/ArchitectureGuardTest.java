package dev.workbench.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.springframework.web.bind.annotation.RestController;

/** 固化分层边界，防止重构成果再次退化。 */
@AnalyzeClasses(packages = "dev.workbench", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureGuardTest {

  /** 顶层业务包必须构成有向无环图。 */
  @ArchTest
  static final ArchRule topLevelPackagesMustBeFreeOfCycles =
      com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices()
          .matching("dev.workbench.(*)..")
          .should()
          .beFreeOfCycles();

  /** domain 只允许纯领域模型与纯逻辑。 */
  @ArchTest
  static final ArchRule domainMustStayPure =
      noClasses()
          .that()
          .resideInAPackage("dev.workbench.domain..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "dev.workbench.persistence..",
              "dev.workbench.infrastructure..",
              "dev.workbench.orchestration..",
              "dev.workbench.planning..",
              "dev.workbench.task..",
              "org.springframework.web..",
              "jakarta.persistence..");

  /** framework 只保留契约、纯图模型与无状态引擎内核。 */
  @ArchTest
  static final ArchRule frameworkMustNotDependOnBusinessOrPersistence =
      noClasses()
          .that()
          .resideInAPackage("dev.workbench.framework..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "dev.workbench.persistence..",
              "dev.workbench.task..",
              "dev.workbench.bug..",
              "dev.workbench.workspace..",
              "dev.workbench.privateagent..",
              "dev.workbench.planning..",
              "dev.workbench.memory..",
              "dev.workbench.agent..",
              "dev.workbench.orchestration..",
              "dev.workbench.evaluation..",
              "dev.workbench.observability..",
              "dev.workbench.auth..",
              "dev.workbench.audit..",
              "dev.workbench.external..",
              "dev.workbench.report..",
              "org.springframework.web..",
              "io.agentscope..");

  /** AgentScope SDK 只能出现在基础设施适配层；app 仅保留装配配置。 */
  @ArchTest
  static final ArchRule agentScopeSdkMustStayInInfrastructure =
      noClasses()
          .that()
          .resideOutsideOfPackages(
              "dev.workbench.infrastructure..",
              "dev.workbench.persistence.agentscope..",
              "dev.workbench.app..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("io.agentscope..");

  /** Controller 不直接持有 JPA 仓储或 EntityManager。 */
  @ArchTest
  static final ArchRule controllersMustNotDependOnJpa =
      noClasses()
          .that()
          .areAnnotatedWith(RestController.class)
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "org.springframework.data.repository..",
              "jakarta.persistence..",
              "dev.workbench.persistence.repository..",
              "dev.workbench.persistence.entity..");
}
