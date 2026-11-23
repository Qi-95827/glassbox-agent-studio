# PGVector 切换与部署指南

> 适用版本：Flyway `V13`（H2 通用）/ `db/migration-pg` `V1~V2`（PostgreSQL + pgvector）  
> 目标：一套 PostgreSQL 16 + `pgvector` 同时承载关系型业务与向量检索，同时保留 H2 默认单元测试的秒级全绿能力。

## 1. 双轨 Profile 策略

| Profile | 数据库 | Flyway locations | 用途 |
| --- | --- | --- | --- |
| 默认（不激活） | H2 file `./data/workbench` | `classpath:db/migration` | 本地单测、无 Docker 环境、根目录 `mvn test` |
| `postgres` | PostgreSQL 16 + pgvector | `classpath:db/migration-pg` | 标准开发与生产推荐，真实 HNSW 向量检索 |

两条链路各自独立、互不影响：

- H2 链路：`V1~V13`。`V13` 新增 `memory_records.embedding_json`、`embedding_model`，以及 `knowledge_documents`、`knowledge_chunks`、`context_artifacts` 三张通用表。
- PostgreSQL 链路：`V1__pg_baseline.sql` + `V2__pgvector_extension_and_hnsw.sql`。
  - 历史 `V1~V12` 使用 H2 专属语法（`ADD CONSTRAINT IF NOT EXISTS`）与 `CLOB` 类型，PostgreSQL 无法直接执行；
  - 因此 `V1__pg_baseline.sql` 由 H2 执行完 `V1~V13` 后的最终结构等价转换为 PostgreSQL DDL（`CLOB -> TEXT`、标识符小写、约束/索引/外键完整保留），并已纳入版本控制；
  - `V2` 启用 `CREATE EXTENSION vector`，为 `memory_records.embedding_vec` 与 `knowledge_chunks.embedding_vec` 增加 `vector(1536)` 列与 HNSW 余弦索引。

> 升级须知：如后续新增通用迁移，请同时在 `db/migration` 增加 H2 版本，并在 PostgreSQL 链路上追加等价迁移，避免两轨漂移。

## 2. 一键启动

```powershell
cd "D:\software program\AI\agent-dev-workbench"
docker compose up -d
```

默认容器：

- 镜像：`pgvector/pgvector:pg16`
- 数据库/用户/密码：`workbench` / `workbench` / `workbench`
- 宿主端口：`5432`
- 命名卷：`agent-dev-workbench_workbench-pg-data`（持久化）
- 健康检查：`pg_isready`

可用环境变量覆盖：

| 变量 | 默认值 |
| --- | --- |
| `WORKBENCH_PG_DATABASE` | `workbench` |
| `WORKBENCH_PG_USER` | `workbench` |
| `WORKBENCH_PG_PASSWORD` | `workbench` |
| `WORKBENCH_PG_PORT` | `5432` |
| `WORKBENCH_PG_URL` | `jdbc:postgresql://127.0.0.1:5432/workbench` |

## 3. 以 PostgreSQL 档启动工作台

```powershell
$env:WORKBENCH_PG_URL="jdbc:postgresql://127.0.0.1:5432/workbench"
$env:WORKBENCH_PG_USER="workbench"
$env:WORKBENCH_PG_PASSWORD="workbench"
mvn spring-boot:run "-Dspring-boot.run.profiles=postgres"
```

启动日志应出现：

```text
Migrating schema "public" to version "1 - pg baseline"
Migrating schema "public" to version "2 - pgvector extension and hnsw"
Successfully applied 2 migrations
Started WorkbenchApplication
```

回退到 H2 只需不激活 `postgres` Profile；默认配置不会触碰 PostgreSQL。

## 4. 向量检索与隔离规则

- 长期记忆：`MemoryRetrievalService` 在工作区 ACTIVE 语料上实时计算 BM25 / IDF，再与向量余弦按
  `1 / (60 + rank)` 做 RRF 融合；冷启动硬门槛 `max(bm25, cosine) < 0.12` 直接剔除。
- PostgreSQL：`JdbcMemoryVectorIndex` 通过 `ORDER BY embedding_vec <=> :vec LIMIT k` 下推，
  SQL 强制 `workspace_id = ? AND status = 'ACTIVE'`。
- H2：没有 pgvector 时在内存中做精确余弦，结果与 PG 语义一致。
- 知识库：`knowledge_chunks` 同样带 `workspace_id` 过滤；`CANDIDATE` 记忆永不建索引、永不召回。
- 所有 Embedding 调用失败均静默降级为 BM25，不阻塞任务主流程。

## 5. 可选 Embedding 端点

在「设置 → Embedding 向量模型（可选）」中：

1. 优先选择已保存的 BYOK 连接（复用其 Base URL、模型名与密钥）；
2. 或清空连接，填写独立的 OpenAI 兼容 Base URL（如 `https://api.siliconflow.cn/v1`）与模型名；
3. 向量维度默认 `1536`，必须与端点实际输出一致，否则该批次向量会被丢弃并降级 BM25。

设置只写入 `data/rag-embedding-settings.json`（连接标识、Base URL、模型名、维度），
**不保存任何 API Key**；独立端点的密钥通过服务端环境变量 `workbench.rag.embedding.api-key` 提供。

对应 `application.properties`：

```properties
workbench.rag.embedding.enabled=false
workbench.rag.embedding.connection-id=
workbench.rag.embedding.base-url=
workbench.rag.embedding.model=
workbench.rag.embedding.api-key=
workbench.rag.embedding.dimension=1536
workbench.rag.embedding.timeout-seconds=20
workbench.rag.embedding.batch-size=32
```

## 6. 验证清单

```powershell
# H2 默认全量（ArchUnit 5/5，无需 Docker）
mvn test

# PostgreSQL + pgvector 集成验证（需要容器与已执行迁移）
$env:WORKBENCH_PG_TEST="true"
mvn "-Dtest=PostgresPgVectorIntegrationTest" test
```

集成测试覆盖：

- `vector` 扩展已启用；
- `idx_memory_records_embedding_vec`、`idx_knowledge_chunks_embedding_vec` 使用 HNSW；
- `knowledge_documents` / `knowledge_chunks` / `context_artifacts` 存在；
- 相同向量跨工作区插入时，带 `workspace_id` 的最近邻查询只返回当前工作区记录。

## 7. 常见问题

| 现象 | 处理 |
| --- | --- |
| `extension "vector" is not available` | 确认使用 `pgvector/pgvector:pg16` 镜像，而非官方 `postgres:16` |
| Flyway 报 `type "clob" does not exist` | PostgreSQL Profile 误用了 `db/migration`；确认 `spring.flyway.locations=classpath:db/migration-pg` |
| 端口 5432 被占用 | 设置 `WORKBENCH_PG_PORT=5433` 并同步 `WORKBENCH_PG_URL` |
| 检索只有词法命中 | 检查 Embedding 设置是否启用、模型是否支持 `/embeddings`、维度是否匹配 |
