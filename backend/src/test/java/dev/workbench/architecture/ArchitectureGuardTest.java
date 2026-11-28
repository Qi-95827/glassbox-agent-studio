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

  /**
   * 主代码禁止 JPA {@code @Lob}：大正文必须走受控列或文件，不能用大对象注解绕过长度与迁移治理。
   *
   * <p>同时覆盖「类被标注」与「字段/方法/构造器被标注」两种形态；当前全仓零使用，因此零误报。
   */
  @ArchTest
  static final ArchRule mainCodeMustNotUseLob =
      com.tngtech.archunit.lang.CompositeArchRule.of(
              com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses()
                  .should()
                  .beAnnotatedWith(jakarta.persistence.Lob.class))
          .and(
              com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noCodeUnits()
                  .should()
                  .beAnnotatedWith(jakarta.persistence.Lob.class))
          .because("主代码禁止使用 @Lob，大正文必须走受控列或文件");

  /**
   * 出站 HTTP 客户端只能由统一出站封装与其基础设施适配器创建。
   *
   * <p>规则只禁止「创建」客户端（{@code newHttpClient()} / {@code newBuilder()}）：{@code HttpClient} 自带连接池、
   * 代理与超时语义，业务代码自建客户端会绕过 {@code OutboundUrlGuard} 的地址与凭据防护。
   * 复用注入的共享客户端仍然合法，因此不会误伤正常调用方。白名单只有两处：
   *
   * <ul>
   *   <li>{@code dev.workbench.framework.outbound}：唯一的出站客户端工厂；
   *   <li>{@code dev.workbench.infrastructure}：MCP 等协议适配层，必须复用同一套防护。
   * </ul>
   */
  @ArchTest
  static final ArchRule httpClientCreationOnlyInsideOutboundAdapters =
      noClasses()
          .that()
          .resideOutsideOfPackages(
              "dev.workbench.framework.outbound..", "dev.workbench.infrastructure..")
          .should()
          .callMethod(java.net.http.HttpClient.class, "newHttpClient")
          .orShould()
          .callMethod(java.net.http.HttpClient.class, "newBuilder")
          .because("出站 HTTP 客户端必须由统一出站封装创建，禁止业务代码自建客户端绕过防护");
}
