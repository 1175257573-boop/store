-- ============================================================
-- PGvector 向量库表结构
--
-- 前提：本机的 MySQL 5.7 跑 embedding 与向量检索都不划算
--       （无向量索引插件），所以向量侧放 PostgreSQL + PGvector。
--       知识库正文仍在 MySQL（与业务数据同库，省一次连接），
--       这里只存「用于语义检索的最小副本」。
--
-- 为什么要副本而不是直接在 MySQL 存向量：
--   1. MySQL 5.7 无向量索引，只能全表扫，918 条尚可，上万条就崩
--   2. PGvector 的 HNSW 索引在万级召回率与延迟上都明显更优
--   3. BM25 留在 MySQL（已建 ngram 全文索引），两边各用其长
--
-- 维度说明：Qwen3-Embedding-0.6B 输出 1024 维。
--   若换 4B（2560 维）或 8B（4096 维），必须同步改这里并重建索引 ——
--   不同模型的向量空间互不相通，维度也不同。
-- ============================================================

CREATE EXTENSION IF NOT EXISTS vector;

-- ------------------------------------------------------------
-- 向量表
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS kb_vector (
  -- 业务主键：与 MySQL 侧 t_kb_product_chunk.id / t_kb_faq.id 对应
  -- 用 chunk_<id> / faq_<id> 区分来源，避免两侧 ID 撞车
  id           TEXT        PRIMARY KEY,
  content      TEXT        NOT NULL,

  -- 元数据用 jsonb：向量库普遍支持按 metadata 过滤，
  -- jsonb 比多个独立列更灵活（加字段不用改表结构）
  metadata     JSONB       NOT NULL DEFAULT '{}'::jsonb,

  -- 1024 维，对应 Qwen3-Embedding-0.6B
  embedding    vector(1024) NOT NULL,

  create_time  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- HNSW 索引：召回率与延迟的平衡点，比 IVFFlat 召回更稳
-- m=16 / ef_construction=64 是 pgvector 官方推荐的中等规模参数
CREATE INDEX IF NOT EXISTS idx_kb_vector_hnsw
  ON kb_vector USING hnsw (embedding vector_cosine_ops)
  WITH (m = 16, ef_construction = 64);

-- 常用过滤条件建普通索引。metadata 是 jsonb，
-- 若某字段过滤极频繁，可考虑提升为表达式索引：
--   CREATE INDEX idx_kb_cat ON kb_vector ((metadata->>'category_id'));
--   CREATE INDEX idx_kb_src ON kb_vector ((metadata->>'source'));
CREATE INDEX IF NOT EXISTS idx_kb_metadata ON kb_vector USING gin (metadata);

-- ------------------------------------------------------------
-- 未覆盖问题表（兜底闭环）
--
-- 检索无命中时落库，运营定期看高频未覆盖问题，
-- 决定该补什么知识、该加什么同义词。
-- 没有这张表，知识库就是只进不出 —— 永远不知道自己缺什么。
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS kb_unanswered (
  id              BIGSERIAL   PRIMARY KEY,
  session_id      VARCHAR(64),
  user_id         BIGINT,
  query_text      VARCHAR(500) NOT NULL,
  recognized_intent VARCHAR(32),
  top_score       REAL,          -- 最高检索得分，低于阈值说明确实没收录
  retrieved_ids   VARCHAR(255),  -- 逗号分隔的候选 ID，人工复核用
  query_vector    vector(1024),  -- 便于聚类分析：哪些向量彼此接近却都没命中
  status          SMALLINT      NOT NULL DEFAULT 0,  -- 0待处理 1已补知识 2无需补
  create_time     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_unanswered_status ON kb_unanswered (status, create_time DESC);

-- ------------------------------------------------------------
-- 检索日志（可选，用于算召回率）
--
-- 有了它才能回答「Top-10 里有几个是对的」，
-- 否则调 k 值、改分块全凭感觉。
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS kb_retrieval_log (
  id             BIGSERIAL   PRIMARY KEY,
  session_id     VARCHAR(64),
  query_text     VARCHAR(500) NOT NULL,
  bm25_ids       TEXT[],
  vector_ids     TEXT[],
  fused_ids      TEXT[],
  top_score      REAL,
  latency_ms     INT,
  satisfied      SMALLINT,     -- 用户是否继续追问（1=未追问，多半是答好了）
  create_time    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_retrlog_time ON kb_retrieval_log (create_time DESC);
