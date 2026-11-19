# 持久化统一迁移 · 批次 8（启动回填、AgentScope 会话适配）交付记录

> 日期：2026-11-19。依据 [Spring Data JPA 持久化迁移范本](../开发规范/SpringDataJPA持久化迁移范本.md) 第 4 节批次 8。本记录只覆盖本批入口；批次 2–7 的既有修改未改动。

## 1. 本批入口与结构

| 原入口 | 迁移后 |
| --- | --- |
| `framework/event/EventRunSequenceMigration`（`JdbcTemplate`） | `EventRepository` + `SchemaMigrationRepository` |
| `app/AgentStateConfiguration`（`JdbcAgentStateStore`） | `persistence/agentscope/JpaAgentStateStore` |

新增实体与 Repository：

- `persistence/entity/SchemaMigrationEntity`、`persistence/repository/SchemaMigrationRepository`：映射 `workbench_schema_migrations`，预分配主键使用 `Persistable` 插入路径。
- `persistence/entity/AgentScopeSessionEntity`：映射 `agentscope_sessions`，复合键 `(session_id, state_key, item_index)`，`state_data` 为 CLOB，`version` 保留原版本列。
- `EventRepository` 增加 `findUnsequenced` 和条件回填 `assignRunSeq` 两个 JPQL 方法。

新增适配器：`persistence/agentscope/JpaAgentStateStore`，实现 AgentScope 的 `AgentStateStore` 契约。

## 2. 回填与 AgentScope 行为

### 事件序号回填

- 继续使用迁移标记 `event_run_seq_v1`，首次启动先查标记，进入事务后再查一次。
- 仍按 `run_id, id` 顺序读取 `run_seq IS NULL` 的历史事件，按运行分别从 1 递增编号。
- 回填更新仍带 `run_seq IS NULL` 条件，最后写入迁移标记；回填和标记在同一个事务提交。
- 重复启动时标记已存在，不再执行；事务内复查保留原幂等语义。

### AgentScope 会话

- 表名、`用户命名空间:会话ID` 的 slot 形式、JSON 文本、`key:_hash` 列表哈希均沿用 `JdbcAgentStateStore`。
- 单值 `save` 更新时版本加 1，新建时版本为 1；列表项按旧实现以版本 0 插入。
- `saveIfVersion` 保留 `UNVERSIONED`、创建 `0 -> 1`、指定版本 CAS 和冲突返回 `UNVERSIONED` 的语义。
- `save(list)` 保留哈希一致时增量追加、内容变化时整表重写、空列表不写的旧行为。
- `getVersioned` 缺失键返回 `(null, 0)`；`getList` 按 `item_index` 排序；`listSessionIds` 按命名空间前缀过滤并转义 `%`、`_`、`!`。
- `delete(userId, sessionId, key)` 继续使用接口默认空操作，与旧 `JdbcAgentStateStore` 未覆写该方法的实际行为一致。
- 写操作使用 `REQUIRES_NEW` 独立事务，沿用旧 JDBC 存储每次写独立提交、不受调用方业务事务回滚影响的语义。

## 3. 保留的原生查询及理由

- 生产代码本批不再使用 `JdbcTemplate`、`NamedParameterJdbcTemplate` 或直接 JDBC；事件回填和会话读写均为 JPQL。
- 没有新增 `nativeQuery=true`。
- `backend/src/main/resources/schema.sql` 继续保留 `agentscope_sessions` 的建表和索引 DDL。理由：项目配置 `spring.jpa.hibernate.ddl-auto=none`，表结构由 `schema.sql` 管理；该 DDL 与框架原建表语句一致且可重复执行，不删除旧表、不清空旧会话。

## 4. 修改文件

- `backend/src/main/java/dev/workbench/framework/event/EventRunSequenceMigration.java`
- `backend/src/main/java/dev/workbench/app/AgentStateConfiguration.java`
- `backend/src/main/java/dev/workbench/persistence/repository/EventRepository.java`
- `backend/src/main/java/dev/workbench/persistence/entity/AgentScopeSessionEntity.java`（新增）
- `backend/src/main/java/dev/workbench/persistence/entity/SchemaMigrationEntity.java`（新增）
- `backend/src/main/java/dev/workbench/persistence/repository/SchemaMigrationRepository.java`（新增）
- `backend/src/main/java/dev/workbench/persistence/agentscope/JpaAgentStateStore.java`（新增）
- `backend/src/main/resources/schema.sql`
- `backend/src/test/java/dev/workbench/app/AgentStateConfigurationTest.java`

## 5. 未验证项

- 按要求未运行 Maven，未执行集中回归；编译和测试结果尚未验证。
- 未在隔离旧 H2 副本连续启动两次核对历史事件回填、迁移标记和旧会话。
- 未执行真实 AgentScope 会话保存/恢复、恢复检查点和 `/runs/{id}/agents` 页面链路。
- 未验证 H2 之外的数据库方言；本批 JPQL 和 DDL 以 H2 为目标。
