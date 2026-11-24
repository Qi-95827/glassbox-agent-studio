# Top 10 安全止血与核心 P0 缺陷修复 · 逐项验收记录

> **施工依据**：`docs/迭代计划/研发实施文档_Top10安全止血与核心P0缺陷修复.md`
> **记录规则**：每完成 1 个 Task 立即执行仓库根目录全量测试门禁，并在本文件追加真实改动、新增测试与实测数字；
> 未实跑的验证项必须显式说明原因，绝不以计划或推断代替实测。

---

## Task 01 · 封闭 MCP RCE / SSRF 与 RAG Embedding 越权密钥外泄链

- **对应审计编号**：`S-01`、`S-02`、`S-03`、`S-05`
- **完成时间**：2026-11-24
- **本地提交**：见文末「提交记录」

### 1. 改动文件清单（严格限定本 Task 范围）

**新增主代码**

| 文件 | 职责 |
| :--- | :--- |
| `backend/src/main/java/dev/workbench/framework/outbound/OutboundUrlGuard.java` | 统一出站 URL 与 DNS 安全校验器：拒绝 `user:pass@`、查询串、片段；识别十进制整数/八进制/十六进制/简写 IPv4 与 IPv4-mapped IPv6；逐条校验 DNS 全部 A/AAAA；按显式开关决定是否允许本机回环 |
| `backend/src/main/java/dev/workbench/framework/outbound/OutboundHttpClients.java` | 全平台共享的 JDK `HttpClient`：强制 `Redirect.NEVER`、固定连接超时，避免 302 绕过校验与每请求新建客户端 |
| `backend/src/main/java/dev/workbench/infrastructure/model/ModelHttpTransports.java` | 把共享客户端接入 AgentScope 模型传输层，使 SDK 调用同样禁止跟随重定向 |

**修改主代码**

| 文件 | 改动要点 |
| :--- | :--- |
| `backend/src/main/java/dev/workbench/auth/AuthenticationFilter.java` | `ADMIN_ONLY` 增加 `/api/rag-embedding`（全平台唯一向量化端点配置） |
| `backend/src/main/java/dev/workbench/modelconnection/ModelConnectionService.java` | `validateBaseUrl()` 改为委托 `OutboundUrlGuard`，并把拒绝原因映射回本模块既有中英文文案；新增可注入校验策略的测试构造器 |
| `backend/src/main/java/dev/workbench/modelconnection/RagEmbeddingSettings.java` | `save()` 增加管理员校验、`connectionId` 归属校验（`requireEnabledOwned`）与 `baseUrl` 出站校验 |
| `backend/src/main/java/dev/workbench/modelconnection/RagEmbeddingController.java` | 视图/保存/探活三个入口显式 `requireAdmin()`（单机未初始化模式保持可用） |
| `backend/src/main/java/dev/workbench/infrastructure/model/OpenAiCompatibleEmbeddingProvider.java` | 端点解析改为 `requireSafe()` 严格校验后才携带凭据；探活错误经 `SensitiveDataMasker` 脱敏并只回传受控摘要；改用共享客户端 |
| `backend/src/main/java/dev/workbench/infrastructure/model/OpenAICompatibleModelProbe.java` | 聊天探活注入禁止重定向的传输层；Embedding 探活改用共享客户端 |
| `backend/src/main/java/dev/workbench/infrastructure/model/UserModelConnectionFactory.java` | 运行时模型同样注入禁止重定向的传输层 |
| `backend/src/main/java/dev/workbench/infrastructure/mcp/McpServerService.java` | `STDIO` 仅管理员可注册/探测；`STREAMABLE_HTTP` 端点必须通过出站校验且必填；用户 Header 拒绝保留头与换行注入 |
| `backend/src/main/java/dev/workbench/infrastructure/mcp/McpClientManager.java` | 构造 HTTP 传输时注入出站校验器 |
| `backend/src/main/java/dev/workbench/infrastructure/mcp/StdioMcpTransport.java` | 环境变量黑名单（`PATH`/`LD_*`/`NODE_OPTIONS`/`PYTHON*`/`DOCKER_*`/`JAVA_*`/`GIT_*` 等）、参数数量/长度/行内执行开关/目录穿越校验、子进程运行在一次性临时工作目录并在关闭时清理 |
| `backend/src/main/java/dev/workbench/infrastructure/mcp/HttpMcpTransport.java` | 端点强制严格校验、禁止自动重定向、保留头与头注入拒绝、响应长度上限 4 MiB |
| `backend/src/main/resources/application.properties` | 新增 `workbench.outbound.allow-loopback=true`（本机 Ollama 等本地模型服务显式开关；内网地址始终拒绝） |
| `backend/src/main/resources/i18n/messages.properties`、`messages_zh_CN.properties`、`messages_en_US.properties` | 新增 `outbound.*` 与 3 条 `modelConnection.baseUrl*` 文案，三套文件同步补齐 |

**测试**

| 文件 | 说明 |
| :--- | :--- |
| `.../framework/outbound/OutboundUrlGuardTest.java`（新增） | 校验器正反例 32 例 |
| `.../framework/outbound/OutboundMessagesTest.java`（新增） | 新增文案在中英两套资源包中存在且不相同 |
| `.../modelconnection/ModelConnectionSecurityTest.java`（新增） | BYOK Base URL 安全 19 例 |
| `.../modelconnection/RagEmbeddingSettingsSecurityTest.java`（新增） | 全局 Embedding 配置权限与归属 8 例 |
| `.../infrastructure/mcp/StdioMcpTransportSecurityTest.java`（新增） | STDIO 子进程边界 24 例 |
| `.../infrastructure/mcp/HttpMcpTransportSecurityTest.java`（新增） | HTTP MCP 出站与 Header 6 例 |
| `.../infrastructure/mcp/McpServerServiceSecurityTest.java`（新增） | MCP 注册/探测权限 12 例 |
| `.../infrastructure/mcp/McpRagSecurityIntegrationTest.java`（新增） | 真实过滤器链下的 HTTP 403/400 3 例 |
| `.../infrastructure/model/EmbeddingProbeSecurityTest.java`（新增） | 探活脱敏与密钥外带 4 例 |
| `.../infrastructure/mcp/McpJsonRpcClientTest.java`（修改） | 适配 `HttpMcpTransport` 新增校验参数（断言未放宽） |

### 2. 修复要点与安全边界

1. **统一出站判定**：`OutboundUrlGuard` 同时服务 BYOK、Embedding 与 MCP HTTP，避免三处规则各自演化。判定顺序为：协议 → 凭据 → 查询/片段 → 主机字面量（含非标准写法）→ 明文 http 回环白名单 → DNS 全量地址。
2. **非标准 IP 写法**：按 `inet_aton` 语义解析 1~4 段十进制/八进制/十六进制，`2852039166` 等价于 `169.254.169.254` 会被拦截；`127.1`、`0x7f000001`、`0177.0.0.1` 同样拒绝（JDK URI 对部分写法直接判主机无效，两条路径均为拒绝）。
3. **IPv6 与映射地址**：拦截 `::1`、`::`、`fc00::/7`、`fe80::/10`、`ff00::/8`，并把 `::ffff:0:0/96` 与 `::a.b.c.d` 还原为 IPv4 再判定。
4. **DNS 全量校验**：`InetAddress.getAllByName()` 返回的每条记录都要通过；同一域名同时解析出回环与公网地址视为重绑定特征直接拒绝。
5. **回环开关**：`workbench.outbound.allow-loopback` 默认 `false`，本机 `application.properties` 显式设为 `true` 以保留 Ollama 一类本地模型服务；开关只放行回环，私网/链路本地/元数据段始终拒绝。
6. **重定向**：共享 `HttpClient` 与 AgentScope 传输层均为 `Redirect.NEVER`，302 不再可能把已校验地址带到内网。
7. **RAG Embedding 收口**：接口限管理员；`connectionId` 必须属于当前操作人且已启用；自定义 `baseUrl` 通过统一校验后才允许携带服务端密钥；探活错误脱敏后再截断。
8. **MCP 收口**：`STDIO` 仅管理员；环境变量与参数白名单；子进程工作目录一次性隔离；HTTP 端点强制校验并限制保留头与响应体积。

### 3. 红线自检

| 红线 | 自检结论 |
| :--- | :--- |
| 1 双轨数据库架构 | 未引入任何独立向量库；未新增 `JdbcTemplate`；未改 H2/PostgreSQL 双轨配置 |
| 2 历史 Flyway 迁移 | 未改动 `db/migration/V1~V13` 与 `db/migration-pg/V1~V2` 任一字节 |
| 3 ArchUnit 守卫 | `ArchitectureGuardTest` 5/5 通过；未新增顶层包（仅新增 `framework.outbound` 子包）；`domain` 未变；`framework` 未新增业务/持久化/`io.agentscope` 依赖 |
| 4 评估与 UI 资产 | 未触碰 6 个 Hub `packId`、雷达图与 `PublicQaRunner` |
| 5 前端产品设计 | 未改前端任何文件；无 i18n 前端文案变更 |
| 6 范围纪律 | 未下线 `DirectRunExecutor`、未改路由、未改公开 API 路径与兼容字段 |
| 7 假修复 | 无 `@Disabled`/`@Ignore`；未删除或放宽既有断言（`McpJsonRpcClientTest` 仅补构造参数）；无空 `catch` 吞异常 |
| 8 单任务全量回归 | 本 Task 全量 `mvn test` 已全绿后才进入下一任务 |
| 9 未实跑项 | 见第 6 节，已如实标注 |
| 10 敏感凭据 | 未读取/输出/提交 `config/deepseek-api-key.txt`、`config/master.key` 与 `data/` 目录 |
| 11 git push | 仅本地提交，未推送任何远程 |
| 12 代码质量 | 全部改动文件经 `google-java-format 1.28.0` 格式化；新增中文注释说明前提、边界与限制；无未使用 import 与死代码 |

### 4. 新增/修改测试类与核心正反例

| 测试类 | 用例数 | 正常正例 | 攻击/异常/并发反例 |
| :--- | ---: | :--- | :--- |
| `OutboundUrlGuardTest` | 32 | 公网 https 放行、多 A/AAAA 全公网放行、显式开启回环后 `http://127.0.0.1` 与 `http://localhost` 放行、共享客户端不跟随 302 | `[::1]`、`[::ffff:169.254.169.254]`、`fc00::1`、`fe80::1`、`::`、`ff02::1`、`10/172.16/192.168`、`169.254.169.254`、`100.64/10`、`0.0.0.0`、`224.0.0.1`、`198.18/15`、十进制 `2852039166`、`127.1`、`0x7f000001`、`0177.0.0.1`、`169.254.169.254.nip.io`（模拟解析）、回环+公网混合解析、`?redirect=`、`#片段`、`user:pass@`、`ftp:`/`file:`、`127.0.0.256`、`1.2.3.4.5`、无法解析的主机（严格模式拒绝、保存模式放行） |
| `OutboundMessagesTest` | 1 | 18 条新增文案在 `messages_zh_CN` 与 `messages_en_US` 均存在 | 中文/英文完全相同即失败（防止只补一套） |
| `ModelConnectionSecurityTest` | 19 | 公网 https 可探活；显式开启回环后 `http://127.0.0.1:11434` 可用 | 12 组危险 Base URL 全部拒绝且不发起探活；`nip.io` 解析到元数据地址拒绝；回环开关不放行私网；探活错误中的 `sk-`/`Bearer` 被脱敏；他人 `connectionId` 返回“不存在” |
| `RagEmbeddingSettingsSecurityTest` | 8 | 管理员引用自有已启用连接保存成功并落盘 | 普通用户保存被拒且不落盘；他人/停用连接被拒；元数据与 `[::1]` Base URL 被拒；回环需显式开关；摘要不含服务端密钥 |
| `StdioMcpTransportSecurityTest` | 24 | 无害自定义变量可用；子进程运行在专用临时目录且关闭后清理 | 12 个受保护环境变量（`PATH`/`NODE_OPTIONS`/`LD_PRELOAD`/`PYTHONPATH`/`JAVA_TOOL_OPTIONS`/`GIT_SSH_COMMAND` 等）被拒；`-e`/`--eval`/`-c`/`--print`/`-p`/`--exec` 被拒；`..` 穿越被拒；换行/NUL 参数被拒；超长参数数量被拒；非白名单命令被拒 |
| `HttpMcpTransportSecurityTest` | 6 | 公网端点 + 自定义 Header 可构造 | 元数据端点、私网、`[::1]`、`[::ffff:169.254.169.254]` 被拒；`Host`/`Content-Length`/`Transfer-Encoding`/`Connection`/`Content-Type` 保留头被拒；`\r\n` 头注入被拒；空端点被拒 |
| `McpServerServiceSecurityTest` | 12 | 管理员注册/探测 STDIO 成功；普通用户登记公网 HTTP 成功；头配置标记正确 | 普通用户注册/探测 STDIO 403 且不落库；`http→STDIO` 更新同样被拒；元数据/私网/`[::1]` 端点被拒；缺端点被拒；保留头与头注入被拒 |
| `McpRagSecurityIntegrationTest` | 3 | 管理员读写 `/api/rag-embedding` 成功；管理员注册 STDIO 成功；普通用户登记公网 HTTP 成功 | 未登录 401；普通用户 GET/PUT/probe `/api/rag-embedding` 403；普通用户注册 STDIO 403；元数据/私网/保留头 HTTP MCP 400；不存在 `connectionId` 404 |
| `EmbeddingProbeSecurityTest` | 4 | 回环端点探活确实携带 `Bearer <服务端密钥>` | 第三方 500 正文中的 `sk-`/`Bearer` 被脱敏；未通过校验的 `http://169.254.169.254` 与解析到元数据地址的域名不发起请求、不携带密钥；连接内网地址同样不可用 |

### 5. 实测与门禁（真实执行结果）

```powershell
# 仓库根目录，全量后端回归（含 ArchUnit 与 Flyway 兼容测试）
mvn test
```

| 项目 | 实测结果 |
| :--- | :--- |
| 全量测试 | **Tests run: 545, Failures: 0, Errors: 0, Skipped: 14** |
| 构建 | `BUILD SUCCESS`（`EXITCODE=0`，总耗时 3:58 min，完成于 2026-11-24 11:53:04） |
| `ArchitectureGuardTest` | **Tests run: 5, Failures: 0, Errors: 0, Skipped: 0（5/5 通过）** |
| `FlywayMigrationCompatibilityTest` | Tests run: 4, Failures: 0, Errors: 0, Skipped: 0 |
| 本 Task 新增/修改测试类 | `OutboundUrlGuardTest` 32、`OutboundMessagesTest` 1、`ModelConnectionSecurityTest` 19、`RagEmbeddingSettingsSecurityTest` 8、`StdioMcpTransportSecurityTest` 24、`HttpMcpTransportSecurityTest` 6、`McpServerServiceSecurityTest` 12、`McpRagSecurityIntegrationTest` 3、`EmbeddingProbeSecurityTest` 4，全部 0 失败 |

### 6. 未实跑项与已知限制（如实标注）

1. **PostgreSQL / Docker 专项**：Task 01 不涉及数据库迁移与 pgvector，未运行 `WORKBENCH_PG_TEST=true`；默认 H2 全量回归已全绿。
2. **前端门禁**：本 Task 未改动 `frontend/user-workbench/`，未执行 `npm run format:check && lint && test && build`。
3. **DNS 重绑定残余风险**：保存时校验通过后，连接建立前会再次解析并校验，但校验与真实连接之间仍存在解析结果不同的理论窗口；彻底消除需把已校验 IP 锁定到套接字层，本次未实现，已通过禁用自动重定向缩小影响面并在代码注释中标注。
4. **后端文案装配既有问题**：仓库未配置 `spring.messages.basename=i18n/messages`，运行期 `LocalizedMessages` 取不到 `i18n/messages` 资源包时会回退为 key 本身；该缺陷先于本次任务存在且不在 Top 10 清单内，本次仅保证新增 key 在中英两套资源包中齐全（由 `OutboundMessagesTest` 守护），未擅自改动全局国际化装配。

---

## Task 02 · 全量补齐 6 处 IDOR 归属校验、宿主机遗留密钥隔离、Actuator 鉴权与真实审计人

- **对应审计编号**：`S-04`、`S-08`（鉴权/Origin）、`S-11`、`S-14`（审计人）
- **完成时间**：2026-11-24

### 1. 改动文件清单（严格限定本 Task 范围）

**新增主代码**

| 文件 | 职责 |
| :--- | :--- |
| `backend/src/main/java/dev/workbench/framework/context/CompactionScope.java` | 压缩调用的运行归属上下文：`runId` + `ownerScopeKey` + `modelConnectionId` + 用量回调 |
| `backend/src/main/java/dev/workbench/framework/context/CompactionUsageSink.java` | 后台辅助模型调用（L2 摘要）的用量回调端口 |

**修改主代码**

| 文件 | 改动要点 |
| :--- | :--- |
| `workspace/ApprovalService.java` | `decide()` 先按审批所属工作区执行 `ownership.requireWorkspace()`（IDOR 1） |
| `control/ContextArtifactService.java` | 回查前校验 `runId` 非空并 `ownership.requireRun()`；历史无归属产物按不存在处理（IDOR 2） |
| `persistence/artifact/JpaContextArtifactStore.java` | 引用改为「归属 + 运行 + 标题 + 内容」派生，跨用户/跨运行不再共享；无 `runId` 不落盘 |
| `control/MultiTierContextCompactor.java` | `persistArtifact()` 透传真实 `runId`/`ownerScopeKey`；L2 摘要改为带归属调用；无存储时确定性引用也带归属 |
| `framework/context/ContextArtifactStore.java` | `Artifact` 增加 `ownerScopeKey` 字段并说明隔离要求 |
| `framework/context/CompactionRequest.java`、`CompactionSummarizer.java` | 增加可选 `scope`（含 `modelConnectionId`/`ownerScopeKey`）与带归属的摘要重载，保留旧构造器兼容 |
| `framework/agent/execution/AgentRuntime.java` | 新增 `auxiliaryModelUsage()`：后台辅助调用用量单独记账，不干扰主调用配对 |
| `orchestration/ControlledAgentRuntime.java` | 实现辅助用量记账：`CostService.reserve/settle` 计入运行费用台账，超支触发 `StopExecution` |
| `infrastructure/agentscope/ControlledModel.java` | 压缩请求携带运行归属与用量回调；预算停止不再被“压缩失败即降级”吞掉 |
| `infrastructure/agentscope/AgentScopeExecutor.java` | 从运行上下文解析 `runId`/归属键/连接并构造 `CompactionScope` |
| `infrastructure/model/ByokCompactionSummarizer.java` | 只用显式透传的运行连接；无连接时直接降级，绝不读取默认 `LOCAL` 连接；摘要用量回传运行台账 |
| `planning/EntryPlanningController.java` | `taskRoute()` 增加 `ownership.requireTask()`（IDOR 3） |
| `auth/AuthenticationFilter.java` | `ADMIN_ONLY` 增加 `/api/evaluation-datasets`；运维端点同样解析会话身份 |
| `evaluation/ManagedEvaluationDatasetService.java` | 写入类方法补 `auth.requireAdmin()`；`fromFailure()` 校验运行归属；`publish()` 用服务端登录名覆盖请求体发布者（IDOR 4） |
| `evaluation/EvalLabController.java` | Hub `download`/`import` 仅管理员，`GET /hub` 目录浏览保持只读公开（IDOR 5） |
| `auth/SecurityConfiguration.java` | `/actuator/**` 显式要求 `ROLE_ADMIN` |
| `app/LocalOriginFilter.java` | `@Order(HIGHEST_PRECEDENCE)` 并覆盖 `/actuator/` |
| `orchestration/RunCreationService.java`、`planning/PlanCandidateConfirmationService.java` | 使用宿主机全局 DeepSeek 密钥前调用 `requireLegacyKeyAccess()` |
| `infrastructure/model/TaskIdeaModel.java`、`DeepSeekPlanCandidateModel.java` | 在没有自有连接、真正读取宿主机密钥的位置做特权校验（把策略放在密文读取点，避免误伤测试替身） |
| `planning/TaskIdeaService.java`、`privateagent/PrivateAgentDraftService.java`、`planning/PlanCandidateGenerationService.java` | 优先绑定当前用户自有连接；无连接时按特权校验决定是否允许回退 |
| `modelconnection/ModelConnectionService.java` | 新增 `legacyKeyAllowed()` / `requireLegacyKeyAccess()`；`list()` 对普通用户隐藏宿主机全局密钥条目 |
| `audit/AuditService.java` | 操作人改为真实登录账号；无会话后台动作记 `system`，单机模式记 `local-user` |

**测试**

| 文件 | 说明 |
| :--- | :--- |
| `.../auth/Task2IdorIntegrationTest.java`（新增） | 真实过滤器链下的 5 组越权/权限场景 |
| `.../modelconnection/LegacyKeyAccessTest.java`（新增） | 宿主机密钥权限 6 例 |
| `.../audit/AuditActorTest.java`（新增） | 审计操作人 4 例 |
| `.../control/ContextArtifactOwnershipTest.java`（新增） | 产物隔离与回查归属 7 例 |
| `.../infrastructure/model/ByokCompactionSummarizerTest.java`（新增） | 摘要连接归属与用量回传 5 例 |
| `.../agent/ControlledModelCompactionTest.java`（新增） | 压缩上下文透传与预算停止 3 例 |
| `WorkbenchIntegrationTest`、`BugDockerIntegrationTest`、`WorkspaceServiceTest`、`PrivateAgentDraftModelIntegrationTest`（适配） | 仅为新增依赖/新签名补参数与管理员身份，未放宽任何原断言 |

### 2. 修复要点

1. **六处 IDOR 全部收口**：审批决策、上下文产物、任务路由、评测数据集、Hub 导入口、异步压缩，均先解析真实归属再动数据；引用/标识不再被当作授权凭证。
2. **产物按运行隔离**：`art-*` 引用由「归属 + 运行 + 内容」派生，不同用户即使卸载完全相同正文也不会共用记录；没有 `runId` 的历史记录不再可读。
3. **压缩不再回退 `LOCAL`**：`CompactionRequest` 携带运行归属，`ControlledModel` 从运行上下文透传，`ByokCompactionSummarizer` 只认显式连接；无连接时降级本地规则摘要。
4. **摘要用量计入台账**：`AgentRuntime.auxiliaryModelUsage()` → `CostService.reserve/settle`，超支时抛 `StopExecution` 并穿透压缩降级。
5. **宿主机全局密钥隔离**：`legacyKeyAllowed()` 仅管理员或已领取历史的账号可用；任务创建、候选确认、计划生成、想法草稿、私有 Agent 草稿五条入口全部加校验；列表中隐藏该条目。
6. **运维端点与来源校验**：`/actuator/**` 需 `ROLE_ADMIN`（未登录 401、普通用户 403、管理员 200）；`LocalOriginFilter` 提到最高优先级并覆盖运维路径。
7. **真实审计人**：有会话记账号名，无会话后台动作记 `system`，单机未初始化记 `local-user`。

### 3. 红线自检

| 红线 | 自检结论 |
| :--- | :--- |
| 1 双轨数据库架构 | 未新增任何独立向量库与 `JdbcTemplate`；未改双轨配置 |
| 2 历史 Flyway 迁移 | **未新增、未修改任何迁移脚本**（本次无需表结构变更：产物隔离通过引用派生实现） |
| 3 ArchUnit 守卫 | 5/5 通过；未新增顶层包；`framework.context` 新增类仅依赖 framework 自身 |
| 4 评估与 UI 资产 | 6 个 Hub `packId`、雷达图、`PublicQaRunner` 均未触碰 |
| 5 前端产品设计 | 未改前端任何文件 |
| 6 范围纪律 | 未改公开 API 路径/兼容字段；`AuditService.record` 签名保持不变 |
| 7 假修复 | 无 `@Disabled`/`@Ignore`；4 个既有测试仅为新增依赖补参数/管理员身份，断言未放宽 |
| 8 单任务全量回归 | 本 Task 全量 `mvn test` 全绿后才继续 |
| 9 未实跑项 | 见第 5 节 |
| 10 敏感凭据 | 未读取/输出/提交 `config/*` 与 `data/`；宿主机密钥相关测试全部使用权限判定，不触发真实外部调用 |
| 11 git push | 仅本地提交 |
| 12 代码质量 | 全部改动经 `google-java-format 1.28.0`；注释说明前提与边界 |

### 4. 新增/修改测试类与核心正反例

| 测试类 | 用例数 | 正常正例 | 攻击/异常反例 |
| :--- | ---: | :--- | :--- |
| `Task2IdorIntegrationTest` | 5 | 工作区所有者本人可批准；本人可读任务路由与产物；管理员可访问 Actuator；管理员登录名可读 | 他人审批 403 且状态仍 `PENDING`；他人任务路由 403；他人产物 403；无 `runId` 产物 404；普通用户评测数据集/Hub 导入 403；管理员引用他人运行仍 403 且不写库；未登录 Actuator 401、普通用户 403；普通用户 `deepseek` 任务 403 且不创建运行 |
| `ContextArtifactOwnershipTest` | 7 | 同一运行同一归属重复卸载幂等复用；归属者回查成功 | 不同运行/不同账号同内容引用不同；无运行归属不落盘；历史无 `runId` 404；他人运行 403；非法引用不查库 |
| `ByokCompactionSummarizerTest` | 5 | 使用运行连接完成摘要并回传 11/5 Token，请求携带该连接的 Bearer | 旧三参调用、无连接 scope、停用连接都不发起模型调用；`defaultConnectionId()` 从未被调用（证明无 `LOCAL` 回退）；关闭开关时不调用 |
| `ControlledModelCompactionTest` | 3 | 压缩请求携带 `runId`/归属/连接；用量回调落到 `runtime.auxiliaryModelUsage` | 压缩抛出的 `StopExecution` 穿透且不派发模型调用；旧构造器保持空归属 |
| `LegacyKeyAccessTest` | 6 | 单机模式、管理员、已领取历史账号可用 | 普通用户、后台无会话线程被拒；列表中看不到宿主机密钥条目 |
| `AuditActorTest` | 4 | 登录账号名入库；单机模式 `local-user` | 初始化后无会话记 `system`；超长说明截断到 1000 且 `outcome` 默认 `RECORDED` |

### 5. 实测与门禁

| 项目 | 实测结果 |
| :--- | :--- |
| 全量测试 | **Tests run: 575, Failures: 0, Errors: 0, Skipped: 14** |
| 构建 | `BUILD SUCCESS`（`EXITCODE=0`，3:57 min，完成于 2026-11-24 12:32:06） |
| `ArchitectureGuardTest` | **5/5 通过** |
| 本 Task 新增测试类 | `Task2IdorIntegrationTest` 5、`ContextArtifactOwnershipTest` 7、`ByokCompactionSummarizerTest` 5、`ControlledModelCompactionTest` 3、`LegacyKeyAccessTest` 6、`AuditActorTest` 4，全部 0 失败 |

**未实跑项**：Task 02 不涉及数据库迁移与 pgvector，未运行 `WORKBENCH_PG_TEST=true`；未改动前端，未执行前端门禁。

---

## Task 03 · 修复 PostgreSQL + pgvector 运行期 JPA 崩溃与系统自检失败

- **对应审计编号**：`F-02`
- **完成时间**：2026-11-24

### 1. 改动文件清单（严格限定本 Task 范围）

**主代码**

| 文件 | 改动要点 |
| :--- | :--- |
| `backend/src/main/java/dev/workbench/persistence/entity/*.java`（33 个实体，69 处字段） | `@Lob` → `@JdbcTypeCode(SqlTypes.LONGVARCHAR)`；Hibernate 6 下同时兼容 H2 的 `CLOB` 与 PostgreSQL 的 `TEXT`，不再被 pgjdbc 当作 OID 大对象 |
| `PlanNodeAgentBindingEntity`、`PrivateAgentEntity`、`PrivateAgentVersionEntity`、`PrivateSkillEntity`、`PrivateSkillVersionEntity`、`WorkflowAttemptEntity` | 移除硬编码 `columnDefinition = "CLOB"`（PostgreSQL 无此类型） |
| `backend/src/main/java/dev/workbench/persistence/repository/SystemCatalogRepository.java` | 自检 SQL 改为 `LOWER(table_schema) = 'public'`，H2（`PUBLIC`）与 PostgreSQL（`public`）都能正确计数 |
| `backend/src/main/java/dev/workbench/infrastructure/vector/PgVectorSupport.java`（新增） | 探测向量列声明维度、收敛 `hnsw.ef_search`、在事务内安全设置候选池 |
| `backend/src/main/java/dev/workbench/infrastructure/vector/JdbcKnowledgeVectorIndex.java` | 写入前做维度一致校验；维度不一致或数据库拒绝时记 WARN 并跳过向量列，绝不回滚外层入库事务；召回支持 `hnsw.ef_search` |
| `backend/src/main/java/dev/workbench/infrastructure/vector/JdbcMemoryVectorIndex.java` | 同上（记忆回填事务同样不能被向量写入打断） |
| `backend/src/main/resources/db/migration-pg/V3__pg_legacy_migration_markers.sql`（新增） | 幂等补齐 `tool_budgets_v2`/`workflow_check_v1`/`workflow_plan_v1` 三条历史迁移标记，与 H2 保持一致 |

**测试**

| 文件 | 说明 |
| :--- | :--- |
| `.../infrastructure/vector/PgVectorSupportTest.java`（新增） | 维度探测、未知列处理、ef_search 收敛与失败降级 4 例 |
| `.../infrastructure/vector/JdbcVectorIndexDegradationTest.java`（新增） | 写入/召回在 PG 方言故障下只降级不抛异常 3 例 |
| `.../persistence/PostgresPgVectorIntegrationTest.java`（扩展） | 保留原 HNSW/隔离验证，新增迁移标记断言、`countPublicTables() > 10` 与 5 个实体 >10KB 中文长文本的 JPA 真实读写 |

> **迁移编号说明**：Task 03 必须独立提交且 Flyway 版本号不可重复，因此本任务的 PG 历史标记使用
> **`V3`**；原计划在 Task 04 落地的核心索引迁移顺延为 PG **`V4`**（H2 仍为 `V14`）。历史脚本
> `db/migration/V1~V13`、`db/migration-pg/V1~V2` 一字节未改。

### 2. 修复要点

1. **长文本映射统一**：69 处 `@Lob String` 全部改为 `SqlTypes.LONGVARCHAR`，这是 Hibernate 6 下同时适配 H2 `CLOB` 与 PostgreSQL `TEXT` 的唯一稳妥写法；未改动任何历史迁移。
2. **自检大小写**：`information_schema.tables` 的 schema 名在两种库中大小写不同，统一 `LOWER(...)` 比较，避免 PostgreSQL 恒判 0 表而 `SelfCheck` 报 FAIL。
3. **向量写入安全降级**：写入前用 `pg_attribute + format_type` 读出列声明维度；不一致时 WARN 并保留 `embedding_json`；任何数据库异常都被限制在向量列，不打断文档入库与记忆回填事务。
4. **召回质量**：`nearest()` 在只读事务内执行 `SET LOCAL hnsw.ef_search = N`（N 来自 `workbench.vector.hnsw.ef-search`，默认 100，收敛到 1~1000），缓解小工作区被 HNSW 过筛成 0 条的问题，且不污染连接池连接。

### 3. 红线自检

| 红线 | 自检结论 |
| :--- | :--- |
| 1 双轨数据库架构 | 保持 H2 默认 + PostgreSQL 16/pgvector 可选双轨；未引入独立向量库；未新增 `JdbcTemplate` 业务绕行（两个向量索引本就是既有 pgvector 通道） |
| 2 历史 Flyway 迁移 | `db/migration/V1~V13`、`db/migration-pg/V1~V2` **一字节未改**；只新增 PG `V3` |
| 3 ArchUnit 守卫 | 5/5 通过；未新增顶层包；实体仅新增 Hibernate 注解 |
| 4 评估与 UI 资产 | 未触碰 |
| 5 前端产品设计 | 未改前端 |
| 6 范围纪律 | 未改公开 API；未顺手做索引/性能改造（留给 Task 04） |
| 7 假修复 | 无 `@Disabled`/`@Ignore`；PG 测试保持环境变量门禁；没有把无法实跑的测试写成"已通过" |
| 8 单任务全量回归 | 本 Task 全量 `mvn test` 全绿后才继续 |
| 9 未实跑项 | 本机 Docker 守护进程未运行（`docker version` 报 `dockerDesktopLinuxEngine` 管道不存在），**PostgreSQL 集成测试未实跑** |
| 10 敏感凭据 | 未读取/输出/提交 `config/*` 与 `data/` |
| 11 git push | 仅本地提交 |
| 12 代码质量 | 全部改动经 `google-java-format 1.28.0`；新增注释说明 H2/PG 差异与降级边界 |

### 4. 新增/修改测试类与核心正反例

| 测试类 | 用例数 | 正常正例 | 异常反例 |
| :--- | ---: | :--- | :--- |
| `PgVectorSupportTest` | 4 | `vector(1536)` 正确解析为 1536；合法 ef_search 原样返回 | 列类型为 `text`、列不存在、查询抛异常、传 null → 维度 0；ef_search 0/-20 收敛到 1、5000 收敛到 1000；`SET LOCAL` 失败不抛异常 |
| `JdbcVectorIndexDegradationTest` | 3 | PG 方言下 `store`/`nearest` 全程不抛异常并返回空召回；H2 数据源始终 `available()=false` 且不发起任何语句 | 维度不匹配/扩展缺失（模拟连接拒绝所有语句）时只降级 |
| `PostgresPgVectorIntegrationTest` | 3（环境变量门禁） | pgvector 扩展 + HNSW 索引 + V13 表 + 三条迁移标记；`countPublicTables()>10`；5 个实体 >10KB 中文长文本写入并原样回读 | 工作区级向量召回不得串工作区（原有用例保留） |

### 5. 实测与门禁

| 项目 | 实测结果 |
| :--- | :--- |
| 全量测试（H2 默认档） | **Tests run: 584, Failures: 0, Errors: 0, Skipped: 16** |
| 构建 | `BUILD SUCCESS`（`EXITCODE=0`，4:01 min，完成于 2026-11-24 12:48:04） |
| `ArchitectureGuardTest` | **5/5 通过** |
| `FlywayMigrationCompatibilityTest` | Tests run: 4, Failures: 0, Errors: 0 |
| PostgreSQL 专项 | **未实跑**：本机 Docker 守护进程未运行（`docker version` 连接 `npipe:////./pipe/dockerDesktopLinuxEngine` 失败），无法执行 `docker compose up -d` 与 `WORKBENCH_PG_TEST=true mvn -Dtest=PostgresPgVectorIntegrationTest test`；已在第 6 节如实标注 |

### 6. 未实跑项与已知限制

1. **PostgreSQL 集成测试未执行**：Docker Desktop 引擎未启动，`PostgresPgVectorIntegrationTest` 的 3 个用例（含新增的 JPA 长文本读写）处于环境变量门禁下被跳过（`Skipped` 计数包含该类的 3 例）。该类已按 postgres Profile 装配真实 Spring 上下文，需要具备 PG 16 + pgvector 的环境后执行：
   `docker compose up -d` 然后 `$env:WORKBENCH_PG_TEST="true"; mvn "-Dtest=PostgresPgVectorIntegrationTest" test`
2. **向量列维度仍固定为 1536**：本次按"维度不一致则跳过向量列、保留 `embedding_json`"实现安全降级，未改列定义（改列需要新的迁移与重建索引，属于 Task 04/后续演进范围）。
3. **前端门禁**：未改动前端，未执行前端检查。
---

## Task 04 · 拆除 RAG 检索 JVM OOM 炸弹、补齐核心索引与指标快照

- **对应审计编号**：`P-01`、`P-02`（快照部分）、`P-04`（任务列表全表扫描）
- **完成时间**：2026-11-24

### 1. 改动文件清单（严格限定本 Task 范围）

**主代码**

| 文件 | 改动要点 |
| :--- | :--- |
| `persistence/view/KnowledgeChunkSummary.java`（新增） | 知识库检索轻量投影：id/文档/标题/正文/Token 估算，**不含 `embedding_json`** |
| `persistence/view/MemoryRecordSummary.java`（新增） | 记忆召回轻量投影：打分与门槛所需字段，**不含 `embedding_json`** |
| `persistence/repository/KnowledgeChunkRepository.java` | 新增 `summarizeByWorkspace()` JPQL 投影查询 |
| `persistence/repository/MemoryRecordRepository.java` | 新增 `summarizeByWorkspaceAndStatus()` JPQL 投影查询 |
| `memory/knowledge/KnowledgeRagService.java` | 检索改为「投影全量 BM25 + 候选集向量重排」；`index()` 拆成「事务外 Embedding + 短事务落库」（`TransactionTemplate`）；`storeAll()` 批量写向量 |
| `memory/MemoryRetrievalService.java` | 同上：先算查询向量，再投影预筛，候选集（词法 Top-N ∪ 向量下推 Top-N）才回表算余弦 |
| `infrastructure/vector/JdbcKnowledgeVectorIndex.java` | 新增 `storeAll()`：一次 `batchUpdate` 写入整篇分块；维度不一致整体跳过；异常只告警 |
| `framework/memory/KnowledgeVectorIndex.java` | 新增默认 `storeAll()` 契约（默认逐条，PG 实现覆盖为批量） |
| `infrastructure/model/OpenAiCompatibleEmbeddingProvider.java` | `embedAll()` 按 `data[].index` 归位；缺条、维度不一致、长度不符整体返回空（fail-fast），杜绝按位置错配 |
| `persistence/repository/TaskRepository.java` | 新增 `findExistingIds()` 与 `findIdsWithoutOwnership()` 两条精确查询 |
| `ownership/OwnershipService.java` | `readableTaskIds()` 不再 `findAll()` 扫全表，改为按归属集合精确取号 + 单独取历史无归属任务 |
| `observability/DatabaseMetrics.java` | 改为按窗口复用的不可变 `Snapshot`：默认 15 秒（`workbench.metrics.snapshot.ttl-seconds`）一次刷新，所有 Gauge 只读快照；`refreshNow()` 供强制刷新；`modelUsage()/toolUsage()` 为空按 0 处理 |
| `evaluation/grader/LlmJudgeGrader.java`、`evaluation/EvalBenchmarkHubService.java` | 复用共享 `OutboundHttpClients`（禁止重定向、复用连接），不再每请求新建客户端 |
| `db/migration/V14__core_query_indexes.sql`（新增，H2） | runs / 三类 invocations / events / tasks / resource_ownership / tool_approval_requests / eval_lab_runs 的复合二级索引 |
| `db/migration-pg/V4__pg_core_query_indexes.sql`（新增，PostgreSQL） | 与 V14 等价的索引集合 |

**测试**

| 文件 | 说明 |
| :--- | :--- |
| `.../memory/MemoryRetrievalProjectionTest.java`（新增） | 投影召回与候选上界 2 例 |
| `.../memory/knowledge/KnowledgeRagProjectionTest.java`（新增） | 知识库投影召回 2 例 |
| `.../observability/DatabaseMetricsSnapshotTest.java`（新增） | 快照窗口、强制刷新、窗口为 0 与 Gauge 注册 5 例 |
| `.../ownership/OwnershipReadableTasksTest.java`（新增） | 任务可见性集合 3 例 |
| `.../infrastructure/model/EmbeddingBatchOrderingTest.java`（新增） | 乱序归位、缺条/维度不一致 fail-fast 4 例 |
| `MemoryRetrievalServiceTest`、`FlywayMigrationCompatibilityTest`、`WorkbenchIntegrationTest`（适配） | 新增投影/候选桩、最新迁移版本改为 V14、指标缓存窗口在集成测试内设为 0；断言未放宽 |

### 2. 修复要点

1. **不再整库反序列化向量**：检索先用不含 `embedding_json` 的投影在数据库侧取轻量行，BM25 仍在全量语料上计算；向量余弦只对「词法 Top-N ∪ 向量下推 Top-N」候选集按需回表，堆占用从 O(全库向量) 降为 O(候选数)。
2. **PostgreSQL 走索引下推**：pgvector 可用时召回完全由 `<=>` 下推完成，既不回表读 embedding，也不在 JVM 里算余弦。
3. **HTTP 移出事务**：索引先完成远端 Embedding，再用 `TransactionTemplate` 开启短事务写入文档+分块+向量；检索同样先算查询向量再查库，避免长事务占用连接。
4. **批量写向量**：整篇文档一次 `batchUpdate`，维度不一致整体跳过，异常只记 WARN，绝不回滚外层入库事务。
5. **向量与文本严格配对**：`embedAll()` 按 `index` 归位；条数或维度不符整体失败，调用方降级为词法检索。
6. **列表查询去全表扫描**：`readableTaskIds()` 改为精确集合查询（含历史无归属任务），语义与旧实现等价。
7. **指标快照**：一次抓取只读一份不可变快照，默认 15 秒刷新一次，把每次抓取二十多条 COUNT 降为「按窗口一次」。
8. **客户端复用**：裁判与 Hub 下载复用共享 HttpClient（禁止重定向 + 连接复用）。

### 3. 红线自检

| 红线 | 自检结论 |
| :--- | :--- |
| 1 双轨数据库架构 | 保持 H2 + PostgreSQL/pgvector 双轨；未引入独立向量库；向量索引本就是既有 pgvector 通道 |
| 2 历史 Flyway 迁移 | `V1~V13`、`migration-pg/V1~V2` 一字节未改；只新增 H2 `V14` 与 PG `V4`（Task 03 已占用 PG `V3`） |
| 3 ArchUnit 守卫 | 5/5 通过；新增投影接口位于既有 `persistence.view` 包，未新增顶层包 |
| 4 评估与 UI 资产 | Hub 6 个 `packId` 与雷达图未触碰；只替换了下载用的 HttpClient |
| 5 前端产品设计 | 未改前端 |
| 6 范围纪律 | 未改公开 API 与兼容字段；未顺手重写监控看板聚合（`timeSeries` 平方级问题留待后续专项） |
| 7 假修复 | 无 `@Disabled`/`@Ignore`；适配的测试仅改桩/版本号/缓存窗口，断言未放宽 |
| 8 单任务全量回归 | 本 Task 全量 `mvn test` 全绿后才继续 |
| 9 未实跑项 | 见第 5 节 |
| 10 敏感凭据 | 未读取/输出/提交 `config/*` 与 `data/` |
| 11 git push | 仅本地提交 |
| 12 代码质量 | 全部改动经 `google-java-format 1.28.0`；注释说明候选上界、降级与缓存窗口的取舍 |

### 4. 新增/修改测试类与核心正反例

| 测试类 | 用例数 | 正常正例 | 异常/边界反例 |
| :--- | ---: | :--- | :--- |
| `MemoryRetrievalProjectionTest` | 2 | 投影命中返回记忆；候选回表仅限候选 id | 断言旧的整库查询 `findByWorkspaceIdAndStatus...` 与 `findByWorkspaceId...` 从未被调用；空工作区不触发候选查询 |
| `KnowledgeRagProjectionTest` | 2 | 投影命中返回分块与文档标题 | 断言整库查询 `findByWorkspaceIdOrderByDocumentId...` 从未被调用；空查询/空工作区零开销 |
| `DatabaseMetricsSnapshotTest` | 5 | 窗口内多次读取只计数一次；`refreshNow()` 立即生效；窗口为 0 时每次刷新；Gauge 带稳定标签注册成功 | 仓库返回空值时不 NPE，按 0 上报 |
| `OwnershipReadableTasksTest` | 3 | 自有一致任务 + 已领取历史的无归属任务可见；单机模式看到全部历史任务 | 未领取时隐藏历史任务；断言 `findAll()` 从未被调用 |
| `EmbeddingBatchOrderingTest` | 4 | 乱序 `data` 按 `index` 归位 | 缺条整体失败；维度不一致整体失败；空输入不调用端点 |

### 5. 实测与门禁

| 项目 | 实测结果 |
| :--- | :--- |
| 全量测试 | **Tests run: 600, Failures: 0, Errors: 0, Skipped: 16** |
| 构建 | `BUILD SUCCESS`（`EXITCODE=0`，4:12 min，完成于 2026-11-24 13:18:17） |
| `ArchitectureGuardTest` | **5/5 通过** |
| `FlywayMigrationCompatibilityTest` | Tests run: 4, Failures: 0, Errors: 0（最新版本断言更新为 `V14`） |
| 本 Task 新增测试类 | `MemoryRetrievalProjectionTest` 2、`KnowledgeRagProjectionTest` 2、`DatabaseMetricsSnapshotTest` 5、`OwnershipReadableTasksTest` 3、`EmbeddingBatchOrderingTest` 4 |

**未实跑项**：
1. **PostgreSQL 专项**：本机 Docker 守护进程未运行，`WORKBENCH_PG_TEST=true` 未执行；`V4` 索引脚本与「pgvector 下推不加载 embedding」的 PG 路径需在具备 PG 16 + pgvector 的环境验证。
2. **前端门禁**：未改前端，未执行。
3. **监控看板聚合**：`MonitoringDashboardService.timeSeries` 的 `O(B*N)` 嵌套循环与 `range=all` 全量拉取属于审计 `P-04` 的其余子项，不在本 Task 规格列举范围内，未改动。
---

## Task 05 · 修复生产链路四级上下文压缩失效与 OpenAI HTTP 400 协议破坏

- **对应审计编号**：`F-01`
- **完成时间**：2026-11-24

### 1. 改动文件清单（严格限定本 Task 范围）

**主代码**

| 文件 | 改动要点 |
| :--- | :--- |
| `framework/context/ContextMessage.java` | 新增 `originalIndex`（压缩后可精确还原原始 SDK 消息）与 `toolCall()` 判定；保留 4 参/2 参兼容构造 |
| `framework/context/CompactionRequest.java` | 新增 `previousCheckpoint`（上一轮 L2 检查点）与 `withPreviousCheckpoint()`；保留 7/8/9/10 参兼容构造 |
| `infrastructure/agentscope/ControlledModel.java` | `toContextMessages()` 完整遍历 `ContentBlock`：TextBlock 正文、ToolUseBlock 名称/参数 JSON、ToolResultBlock 输出文本；逐条记录原始下标。`rebuild()` 改为按下标精确复用原始 `Msg`（保留全部结构化块），被改写/新生成的消息才重建，工具结果带原 `callId`。压缩时带入/回写同一运行的结构化检查点；预算停止仍穿透不被吞掉 |
| `infrastructure/agentscope/AgentScopeExecutor.java` | 为每次装配创建共享的检查点持有器 `AtomicReference<String>` |
| `control/MultiTierContextCompactor.java` | ①`estimate()` 中英混排：ASCII 按 /4、非 ASCII 按 1.5 token/字符；②新增 `alignCut()`，L2/L3 切分点不得落在「assistant(tool_use)」与「TOOL 结果」之间；③L2 检查点与新通知只插入首段 SYSTEM 之后（首条 USER 之前），并替换旧检查点；④L3 新增已处理集合与「压缩后确实变小」判定，工具结果只缩短不删除、带 tool_use 的 assistant 不单删，杜绝单条消息空转 200 次；⑤L1 卸载提示删除不存在的幽灵工具名 `context_artifact_read`；⑥`previousCheckpointIndex` 兼容已提升到头部的检查点，避免叠加两个 |
| `infrastructure/model/ModelContextWindows.java`（新增） | 上下文窗口推断：已知模型族（DeepSeek/Qwen/GLM/OpenAI/Claude 等）取保守上限，未知与自定义端点取可配置默认值 128000，支持 `workbench.model.context-window` 全局覆盖，**永不返回 0** |
| `infrastructure/model/UserModelConnectionFactory.java`、`DeepSeekModelFactory.java` | 构建模型时显式设置 `contextWindowSize(...)`，生产链路不再永久跳过压缩 |

**测试**

| 文件 | 说明 |
| :--- | :--- |
| `.../control/MultiTierContextCompactorToolPairingTest.java`（新增） | 工具原子组、SYSTEM 位置、L3 终止、中文估算、检查点继承 5 例 |
| `.../agent/ControlledModelBlockExtractionTest.java`（新增） | 块展开、按下标还原、改写结果保持配对 3 例 |
| `.../infrastructure/model/ModelContextWindowsTest.java`（新增） | 窗口推断与配置覆盖 4 例 |
| `MultiTierContextCompactorTest`、`MultiTierContextCompactorQueryAwareTest`（适配） | 检查点位置改为「首条 USER 之前」，断言改为按角色查找；未放宽语义 |

### 2. 修复要点

1. **结构化块不再丢失**：此前只取 `getTextContent()`，`tool_use` 的参数与 `tool_result` 的输出完全不可见，压缩既低估体积也看不到工具语义；现在完整展开并记录原始下标。
2. **精准还原**：旧实现用「角色 + 正文」队列复用 `Msg`，两条相同正文会串位；现在按下标复用，改写消息才重建，工具结果仍带原 `callId`。
3. **窗口非零**：`contextWindowSize` 从未设置 → SDK 返回 0 → 压缩整体跳过；现在按模型族给出保守上限，未知端点用可配置默认值。
4. **中文估算**：中文按 1.5 token/字符，避免纯中文会话被低估 3~6 倍后只能靠供应商 400 兜底。
5. **工具原子组**：L2/L3 的切分点与淘汰对象都遵守「调用 + 结果」不可分割，杜绝孤儿 `TOOL` 触发 400。
6. **L3 不再空转**：已处理集合 + 体积收益判定，单条消息最多裁剪一次。
7. **协议合规**：检查点与硬保护通知只出现在首条 USER 之前。
8. **增量摘要**：同一运行内上一轮 L2 结构化检查点会被带入下一轮并合并，避免每轮从零重写。
9. **无幽灵工具**：L1 提示只保留 Artifact 引用号，不再诱导模型调用不存在的工具。

### 3. 红线自检

| 红线 | 自检结论 |
| :--- | :--- |
| 1 双轨数据库架构 | 未涉及 |
| 2 历史 Flyway 迁移 | 未改任何迁移脚本 |
| 3 ArchUnit 守卫 | 5/5 通过；未新增顶层包；`framework/context` 只新增 record 组件 |
| 4 评估与 UI 资产 | 未触碰 |
| 5 前端产品设计 | 未改前端 |
| 6 范围纪律 | 未改公开 API 路径；`ContextMessage`/`CompactionRequest` 均保留旧构造器 |
| 7 假修复 | 无 `@Disabled`；两个既有测试的断言改为「按角色查找首条 USER」，语义未放宽 |
| 8 单任务全量回归 | 全量 `mvn test` 全绿后继续 |
| 9 未实跑项 | 未连真实 OpenAI/DeepSeek 端点验证 400 是否消失（需真实密钥）；见第 5 节 |
| 10 敏感凭据 | 未读取/输出/提交 `config/*` 与 `data/` |
| 11 git push | 仅本地提交 |
| 12 代码质量 | 全部改动经 `google-java-format 1.28.0`；注释说明协议约束与降级边界 |

### 4. 新增/修改测试类与核心正反例

| 测试类 | 用例数 | 正常正例 | 异常/协议反例 |
| :--- | ---: | :--- | :--- |
| `MultiTierContextCompactorToolPairingTest` | 5 | L2 后每个 TOOL 都有匹配调用；L3 在逐步收紧的预算下最终触发；中文估算落在 1.2~2 倍字符数区间；带入旧检查点仍能压缩 | L2/L3 后不得出现孤儿 TOOL 或无结果调用；首条 USER 之后不得出现 SYSTEM；同一消息不得重复叠加 `[HardGuard excerpt]`；极小消息不可压缩时不得空转（通知至多 2 条） |
| `ControlledModelBlockExtractionTest` | 3 | tool_use 名称/参数与 tool_result 输出都被提取，原始下标记录正确；相同正文的两条消息按各自对象还原 | 被改写的工具结果必须重建为带原 `callId` 的 `ToolResultBlock`，否则调用/结果失配 |
| `ModelContextWindowsTest` | 4 | 已知模型族得到非零窗口；未知/自定义端点得到默认 128000 | 显式覆盖优先；`0`/负数配置被忽略，绝不返回 0 |

### 5. 实测与门禁

| 项目 | 实测结果 |
| :--- | :--- |
| 全量测试 | **Tests run: 612, Failures: 0, Errors: 0, Skipped: 16** |
| 构建 | `BUILD SUCCESS`（`EXITCODE=0`，3:56 min，完成于 2026-11-24 13:38:44） |
| `ArchitectureGuardTest` | **5/5 通过** |
| 本 Task 新增测试类 | `MultiTierContextCompactorToolPairingTest` 5、`ControlledModelBlockExtractionTest` 3、`ModelContextWindowsTest` 4 |

**未实跑项**：
1. 未用真实 OpenAI/DeepSeek 端点回放「工具配对破坏导致 HTTP 400」的场景（需要真实密钥与外网）；协议合规性由单元测试从消息序列层面守护。
2. 未改动前端，未执行前端门禁。