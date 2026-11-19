# Spring Data JPA 持久化迁移范本与验收

供实现 Agent 接续使用。先读本文件、[代码开发规范](代码开发规范.md)、[关键流程开发与验收约定](关键流程开发与验收约定.md)和[产品交接](../交接/动态Agent重构后_产品开发与验收交接.md)。接手时在当前工作区运行 `git status --short --untracked-files=all`，核对已有修改和新文件。

## 1. 目标与范围

业务层采用 **Controller → Service → Spring Data JPA Repository**。实体放在 `persistence.entity`，Repository、持久化投影及确有需要的自定义实现放在 `persistence.repository`。Controller 处理 HTTP 输入输出，Service 负责业务规则、权限、事务及对外 DTO，Repository 负责数据读写。实体按表和关系映射，Repository 按聚合或查询职责划分。简单场景用一个接口即可。

`spring-boot-starter-data-jpa` 提供 Repository 支持并带入 Hibernate。Hibernate 使用 H2 JDBC 驱动访问数据库。项目使用 `schema.sql` 管理表结构和升级，配置 `spring.jpa.hibernate.ddl-auto=none`、`spring.jpa.open-in-view=false`，沿用 Spring 管理的同一数据源和事务。迁移覆盖已有直接调用 Hibernate 的业务 Store、剩余 `JdbcTemplate` 操作和 Java 启动回填。AgentScope 会话适配单独验收。数据库驱动、框架传递依赖、结构迁移 SQL 和测试中的独立数据库断言属于基础设施。验收以 H2 为准。 [Spring Boot 官方文档](https://docs.spring.io/spring-boot/3.5/reference/data/sql.html#data.sql.jpa-and-spring-data)

## 2. 已接入生产的示例

动态工作流检查点由 Spring Data JPA Repository 管理。根 `pom.xml` 使用 JPA starter，`WorkbenchApplication` 显式扫描 Repository 和实体。Spring Data 提供 Repository 实现。 [启动配置](../../backend/src/main/java/dev/workbench/app/WorkbenchApplication.java)

| 生产代码 | 参照内容 |
| --- | --- |
| [WorkflowCheckpointEntity](../../backend/src/main/java/dev/workbench/persistence/entity/WorkflowCheckpointEntity.java) | `@Entity`、`@Table`、`@Id`、`@Column` 映射表及字段。ORM 生成 UUID 并写入原字符串主键。格式版本和兼容列用命名常量，实际工具次数存到 `tool_calls`。 |
| [WorkflowCheckpointRepository](../../backend/src/main/java/dev/workbench/persistence/repository/WorkflowCheckpointRepository.java) | 继承 `findById`、`saveAndFlush`，通过方法名派生按计划排序及按 ID、计划和格式版本核对归属的查询。 |
| [WorkflowCheckpointData](../../backend/src/main/java/dev/workbench/persistence/repository/WorkflowCheckpointData.java) | 用 Java record 做查询投影。构造参数与实体属性相对应，保留历史可空字段的原有含义。 |
| [WorkflowRecoveryService](../../backend/src/main/java/dev/workbench/task/WorkflowRecoveryService.java) | 使用继承方法和派生方法，转换为恢复域对象或 API DTO。存储操作参加节点结算事务，检查点与 `CHECKPOINT_SAVED` 事件一起提交。 |
| [WorkspaceReadOnlyRecoveryIntegrationTest](../../backend/src/test/java/dev/workbench/task/WorkspaceReadOnlyRecoveryIntegrationTest.java) | 已有回滚和重试、旧用量继承测试。新增断言校验派生查询的顺序、继承查询可读和不同计划拒绝。 |

~~~java
public interface WorkflowCheckpointRepository
    extends JpaRepository<WorkflowCheckpointEntity, String> {

  List<WorkflowCheckpointData> findByPlanIdOrderByCompletedNodesAsc(String planId);

  Optional<WorkflowCheckpointData> findByIdAndPlanIdAndVersion(
      String id, String planId, int version);
}
~~~

示例的后端集中回归共 239 项，失败 0、错误 0、跳过 1。Docker 用例实际执行，成功读取隔离的历史 H2 副本。跳过的是 Windows 符号链接权限用例。

方法名使用实体属性 `planId` 和 `completedNodes`。对应的表列由映射决定，例如 `plan_id`、`completed_nodes`。列表可直接返回投影 DTO，HTTP 继续使用原字段名、类型和缺失值。 [派生查询](https://docs.spring.io/spring-data/jpa/reference/repositories/query-methods-details.html) · [投影](https://docs.spring.io/spring-data/jpa/reference/repositories/projections.html)

## 3. 查询与写入规则

| 场景 | 采用方式 |
| --- | --- |
| 主键查询、基本读写 | 优先用继承的 `findById`、`existsById`、`save` 等方法。任务或工作区隔离要求放在实际查询和业务校验中。 |
| 常规过滤、排序、计数、分页 | 优先用 `findBy...`、`findFirstBy...`、`existsBy...`、`countBy...` 或 `Pageable`。原接口是游标时保留游标含义和稳定排序。 |
| 列表和汇总 | 使用 record DTO 或封闭接口投影。明确聚合、空值和数值类型，避免逐行重复查询。 |
| 连接、聚合、复杂证据 | 在 Repository 用 `@Query` 或自定义实现。优先 JPQL，使用 Hibernate HQL 扩展时记录原因并在目标数据库验证。 |
| 行锁、版本或状态条件更新 | `@Lock(PESSIMISTIC_WRITE)` 在服务事务中使用。写入用 `@Modifying @Query` 保留状态、版本及归属条件，按受影响行数判定成功或冲突。 |
| 数据库特有操作或元数据 | 确需原生 SQL 时在 Repository 内参数化执行，写明原因、方言和兼容证据。 |

当前 `Session.createSelectionQuery` 中的文本是 Hibernate HQL。常规查询迁移到继承方法或派生方法，复杂查询迁移到 Repository。自定义实现使用 Spring 管理的 `EntityManager`。禁止用通用 SQL 转发器、`Session#doWork` 或在 Repository 内继续调用 `JdbcTemplate` 来包装旧实现。

### 容易迁错的行为与现有参照

- **创建只插入。** 这个范例的检查点由 ORM 生成 UUID，保存前 ID 为空。业务预先分配 ID 的类保留原 ID 来源，明确用 `persist` 或 `Persistable.isNew()` 告诉 Spring Data 这是新行，复用重复主键反例验证旧行无覆盖。固定格式号和乐观锁版本各有语义。 [实体保存规则](https://docs.spring.io/spring-data/jpa/reference/jpa/entity-persistence.html)
- **版本和并发条件。** 对照 [TaskStore](../../backend/src/main/java/dev/workbench/persistence/TaskStore.java) 中归档、锁定和版本更新的语义。状态、版本和受影响行数一起保留。任务或运行不存在时沿用原状态与异常。
- **游标与事务。** 对照 [EventStore](../../backend/src/main/java/dev/workbench/persistence/EventStore.java) 的 `findAfter` 和 [RunStore](../../backend/src/main/java/dev/workbench/persistence/RunStore.java) 的运行行锁。先锁运行，再分配 `run_seq`。同一运行提交顺序、跨页及跨运行隔离必须有证据。
- **批量更新与读回。** 对照 [PlanGraphStore](../../backend/src/main/java/dev/workbench/persistence/PlanGraphStore.java) 的 `approveIfPending`。批量 JPQL 更新绕过已加载实体，按需刷新单个对象、使用标量投影或协调 flush 和 clear。避免清理上下文时丢掉待保存改动。
- **历史状态和数值。** 对照 [InvocationStore](../../backend/src/main/java/dev/workbench/persistence/InvocationStore.java) 的读投影。状态流转使用枚举，历史展示与统计保留未知编码。固定值使用命名常量，可增长版本、数量及耗时使用数值。对照字段长度、空值、CLOB、复合键和数据库默认值。
- **外部副作用。** Repository 加入服务事务。派发以实际提交为准，回滚要按既有规则补偿文件副作用。Git、Docker 和文件系统的授权、目标版本、证据绑定及未知结果语义需要沿用。

## 4. 需要继续迁移的文件与验收路径

以业务闭环为批次，切换后去掉被替代的旧实现。迁移已有 Hibernate Store 时优先复用原实体，同一数据库操作保持一条主路径。

| 批次 | 从现有文件起步 | 该批验收 |
| --- | --- | --- |
| 1. 任务和运行 | `TaskStore`、`RunStore`、`EventStore`、`EntryPolicyStore`、`AuditStore` | 最新运行、同时间按 ID 决胜、无运行旧任务、归档、入口策略、预算、审计和游标保持。事件锁定与排号在同一事务。 |
| 2. 动态执行和历史记录 | `PlanGraphStore`、`PlanStateStore`、`WorkflowExecutionStore`、`WorkflowRecoveryEvidenceStore`、`SupplementStore`、`RunCheckpointStore`、`InvocationStore`、`HandoffStore`、`HandoffEvidenceStore`、`AgentConfigurationStore` | 计划确认、就绪认领、限流重试、调用、检查、交接及旧检查点行为一致。补充失败可重试，恢复继承量准确，未知状态保持可读。 |
| 3. 工作区与 Git | `workspace/WorkspaceService`、`ApprovalService`、`GitDeliveryService`、`WorkspaceCommitService`、`RunHandoverService` | 声明文件仍按复合键。补充同名并发至多一个成功，失败补偿只清理本次创建。批准后的目标地址、源提交和被合入提交保持绑定。重复人工接手后仍需重新验证的状态保留。冲突和未知结果可核对。 |
| 4. Bug | `bug/BugService`、`BugIntakeService` | 原始描述、来源、提交序号、重复链、逐用例证据保留。检测与用例写入完整，分诊和交付一致。修改工作区后旧证据显示过期。 |
| 5. 评测 | `evaluation/EvaluationRepository`、`EvaluationSuiteService`、`BugEvidenceAssessmentService` | 套件版本不可变，批次与实际预算可追溯，预算不同仍不可比。失败案例跳转和评估报告字段保留。 |
| 6. 项目记忆 | `memory/MemoryService` | 工作区归属、字面搜索、版本及历史、软删除和重装配行为一致。版本与历史记录一起结算。 |
| 7. 观测和自检 | `observability/AlertService`、`DatabaseMetrics`、`MonitoringService`、`SelfCheckService` | 复用 Run、Event 和调用聚合。告警、监控统计、未知用量和自检结果一致。系统目录查询确需 SQL 时在 Repository 记录理由。 |
| 8. 启动回填和收尾 | `framework/event/EventRunSequenceMigration`、剩余业务 Session 桥接。AgentScope 会话从 `app/AgentStateConfiguration` 单独处理 | 回填与 `event_run_seq_v1` 标记同事务，旧编号、顺序和重复启动行为可核对。会话适配遵守原 `AgentStateStore` 契约，验证既有会话、命名空间和恢复检查点后清理旧适配。 |

批次 1–2 涉及 **15 个直接使用 Hibernate 的 Store**。批次 3–8 涉及 **16 个直接使用 `JdbcTemplate` 的 Java 类**。AgentScope 的框架 JDBC 状态存储单独计数。检查点采用 JPA Repository 的示例已完成。确认源码现状时同时核对源文件、新文件和调用者。

## 5. 最终验收标准

1. **结构和扫描。** Controller 通过 Service，业务数据库操作走 Spring Data JPA。普通查询使用继承或派生方法，复杂查询只出现在 Repository。生产业务及回填代码中没有 `JdbcTemplate`、`NamedParameterJdbcTemplate` 或直接 JDBC 查询。已迁移的旧 Store 找不到调用者及重复实现。记录各处原生查询的理由。
2. **契约和事务。** 用同一份隔离样本对照请求、响应、顺序、空值、归属与异常。对有风险的写入验证主键冲突、版本冲突、回滚、重试与下游触发。断言真实状态和落库记录。禁止用提前派发或假造成功事件替代提交结果。
3. **历史库和恢复。** 在同一份隔离旧 H2 副本连续启动两次，核对历史行、迁移标记、旧字段和实际恢复证据。涉及 AgentScope 时复核历史会话。测试数据源指向隔离副本。
4. **集中回归。** 每个闭环完成后集中验证一次。复用 `TaskCreationTransactionIntegrationTest`、`EventPersistenceIntegrationTest`、`WorkspaceReadOnlyRecoveryIntegrationTest`、`LegacyH2StartupIntegrationTest` 以及各模块的已有集成用例。补齐该批独有的反例。整轮运行带 Docker 的后端 Maven 回归，涉及浏览器契约时增加相应验证。

~~~powershell
rg -n 'JdbcTemplate|NamedParameterJdbcTemplate|JdbcClient|prepareStatement|DriverManager|doReturningWork|doWork' backend/src/main/java
rg -n 'HibernateSessions|createSelectionQuery|createMutationQuery|createNativeQuery|nativeQuery\s*=\s*true' backend/src/main/java
$env:RUN_DOCKER_TESTS = 'true'
mvn test
~~~

交付记录列出本批入口、Repository、移除的旧路径、保留的原生查询和理由、验证命令与结果、未验证范围及下一批接手点。扫描命中应沿调用路径核查。底层驱动、框架配置和测试用数据库断言分别说明。

## 6. 可直接发给实现 Agent

> 请在 `D:/software program/AI/agent-dev-workbench` 的当前工作区接手，先读 `docs/开发规范/SpringDataJPA持久化迁移范本.md`、`docs/开发规范/代码开发规范.md` 和 `docs/开发规范/关键流程开发与验收约定.md`。以 `WorkflowCheckpointRepository`、`WorkflowCheckpointEntity`、`WorkflowCheckpointData` 为样板，按第 4 节逐批完成 Store 与直接 JDBC 的迁移。常规查询用继承或派生方法，复杂逻辑封装在 Repository。保持原 API、并发条件、事务和历史数据。每个闭环完成后集中回归一次，最终按第 5 节交付扫描、旧 H2 和测试证据。
