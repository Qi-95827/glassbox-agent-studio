-- Phase 2：关系型 + 向量一体化底座。
-- 本迁移同时兼容 H2（默认单元测试）与 PostgreSQL 16 + pgvector：
--   * 通用列/表只用 H2 与 PostgreSQL 都接受的类型（TEXT / VARCHAR / INT / BIGINT）。
--   * pgvector 专属的 vector(1536) 列与 HNSW 索引放在 db/migration-pg/V14，仅在 postgres Profile 执行。
-- 不修改 V1~V12 历史迁移。

-- 1) 记忆向量：JSON 浮点数组由应用层序列化；PG 模式额外维护 embedding_vec。
ALTER TABLE memory_records ADD COLUMN IF NOT EXISTS embedding_json TEXT;
ALTER TABLE memory_records ADD COLUMN IF NOT EXISTS embedding_model VARCHAR(120);

CREATE INDEX IF NOT EXISTS idx_memory_records_embedding_model
  ON memory_records(workspace_id, status, embedding_model);

-- 2) 工作区知识库文档。
CREATE TABLE IF NOT EXISTS knowledge_documents (
  id VARCHAR(36) PRIMARY KEY,
  workspace_id VARCHAR(36) NOT NULL,
  owner_scope_key VARCHAR(80) NOT NULL,
  source_type VARCHAR(40) NOT NULL,
  source_path VARCHAR(1000),
  title VARCHAR(300) NOT NULL,
  content_sha256 VARCHAR(64) NOT NULL,
  chunk_count INT DEFAULT 0 NOT NULL,
  status VARCHAR(30) NOT NULL,
  created_at VARCHAR(40) NOT NULL,
  updated_at VARCHAR(40) NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_knowledge_documents_workspace
  ON knowledge_documents(workspace_id, status);
CREATE UNIQUE INDEX IF NOT EXISTS uq_knowledge_documents_source
  ON knowledge_documents(workspace_id, source_path, content_sha256);

-- 3) 文档分块与向量。
CREATE TABLE IF NOT EXISTS knowledge_chunks (
  id VARCHAR(36) PRIMARY KEY,
  document_id VARCHAR(36) NOT NULL,
  workspace_id VARCHAR(36) NOT NULL,
  chunk_index INT NOT NULL,
  section_title VARCHAR(300),
  content TEXT NOT NULL,
  token_estimate INT DEFAULT 0 NOT NULL,
  embedding_json TEXT,
  embedding_model VARCHAR(120),
  created_at VARCHAR(40) NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_knowledge_chunks_document
  ON knowledge_chunks(document_id, chunk_index);
CREATE INDEX IF NOT EXISTS idx_knowledge_chunks_workspace
  ON knowledge_chunks(workspace_id);
CREATE INDEX IF NOT EXISTS idx_knowledge_chunks_embedding_model
  ON knowledge_chunks(workspace_id, embedding_model);

-- 4) L1 上下文卸载的完整正文落盘与结构化大纲。
CREATE TABLE IF NOT EXISTS context_artifacts (
  id VARCHAR(48) PRIMARY KEY,
  run_id VARCHAR(36),
  tool_call_id VARCHAR(120),
  title VARCHAR(300) NOT NULL,
  content TEXT NOT NULL,
  char_count INT DEFAULT 0 NOT NULL,
  outline_json TEXT,
  created_at VARCHAR(40) NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_context_artifacts_run
  ON context_artifacts(run_id, created_at);
