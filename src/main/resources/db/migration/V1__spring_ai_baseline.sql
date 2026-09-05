-- The two tables Spring AI used to create for itself, written exactly as its initialisers
-- write them, so an existing database is adopted in place: every statement is IF NOT EXISTS,
-- and spring.flyway.baseline-on-migrate lets this version run against a schema that already
-- has them. Conversation ids, history and vectors are kept, not recreated.
--
-- Spring AI's initialisers are switched off (spring.ai.vectorstore.pgvector.initialize-schema,
-- spring.ai.chat.memory.repository.jdbc.initialize-schema) so nothing else issues DDL after
-- this. Flyway serialises concurrent starters with its own Postgres advisory lock.

CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS hstore;
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- PgVectorStore, id-type text, 384 dimensions, HNSW over cosine distance.
CREATE TABLE IF NOT EXISTS public.vector_store (
    id text PRIMARY KEY,
    content text,
    metadata json,
    embedding vector(384)
);
CREATE INDEX IF NOT EXISTS spring_ai_vector_index ON public.vector_store USING hnsw (embedding vector_cosine_ops);

-- JdbcChatMemoryRepository, Postgres dialect.
CREATE TABLE IF NOT EXISTS spring_ai_chat_memory (
    conversation_id VARCHAR(36) NOT NULL,
    content TEXT NOT NULL,
    type VARCHAR(10) NOT NULL CHECK (type IN ('USER', 'ASSISTANT', 'SYSTEM', 'TOOL')),
    "timestamp" TIMESTAMP NOT NULL
);
CREATE INDEX IF NOT EXISTS spring_ai_chat_memory_conversation_id_timestamp_idx
    ON spring_ai_chat_memory (conversation_id, "timestamp");
