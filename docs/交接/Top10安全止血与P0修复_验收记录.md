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
