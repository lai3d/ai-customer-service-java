-- Runs once, on first container start, before the application connects.
-- Spring AI's PgVectorStore can create these itself, but doing it here keeps
-- the application's database role free of superuser-only privileges.
CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS hstore;
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
