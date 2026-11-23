-- PostgreSQL + pgvector 专属迁移，仅在 --spring.profiles.active=postgres 时执行。
-- Flyway locations: classpath:db/migration,classpath:db/migration-pg
-- 需要 pgvector/pgvector:pg16 镜像（见根目录 docker-compose.yml）。

CREATE EXTENSION IF NOT EXISTS vector;

-- 记忆向量列：与 embedding_json 并存，JSON 是 H2 回退格式，embedding_vec 用于 HNSW 下推。
ALTER TABLE memory_records ADD COLUMN IF NOT EXISTS embedding_vec vector(1536);
CREATE INDEX IF NOT EXISTS idx_memory_records_embedding_vec
  ON memory_records USING hnsw (embedding_vec vector_cosine_ops)
  WITH (m = 16, ef_construction = 64);

-- 知识库分块向量列与 HNSW 索引。
ALTER TABLE knowledge_chunks ADD COLUMN IF NOT EXISTS embedding_vec vector(1536);
CREATE INDEX IF NOT EXISTS idx_knowledge_chunks_embedding_vec
  ON knowledge_chunks USING hnsw (embedding_vec vector_cosine_ops)
  WITH (m = 16, ef_construction = 64);
