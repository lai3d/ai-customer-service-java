-- State that used to be per replica, in memory, and is now shared. Two replicas behind one
-- Service used to have their own ticket cap and their own token budget each; see ADR 001.

-- Tickets. The unique constraint is the backstop for deduplication; the cap cannot be a
-- constraint, so it is enforced by locking the conversation's guard row in the transaction
-- that inserts (JdbcTicketOperations).
CREATE SEQUENCE support_ticket_number START WITH 4701;

CREATE TABLE support_ticket (
    ticket_number   varchar(20) PRIMARY KEY,
    conversation_id varchar(36) NOT NULL,
    dedupe_key      text        NOT NULL,
    category        varchar(20) NOT NULL,
    summary         text        NOT NULL,
    order_number    varchar(40),
    created_at      timestamptz NOT NULL,
    CONSTRAINT support_ticket_dedupe UNIQUE (conversation_id, dedupe_key)
);

CREATE TABLE conversation_ticket_guard (
    conversation_id varchar(36) PRIMARY KEY,
    ticket_count    integer     NOT NULL DEFAULT 0
);

-- Token spend per conversation. last_seen lets stale rows be swept: an unbounded table keyed
-- by conversation id is the same leak the old in-memory map was bounded against.
CREATE TABLE conversation_budget (
    conversation_id varchar(36) PRIMARY KEY,
    tokens_spent    bigint      NOT NULL DEFAULT 0,
    last_seen       timestamptz NOT NULL
);

-- One active turn per conversation, across replicas. A lease outlives its turn only if the
-- turn outlived the HTTP read timeout, at which point the turn has already failed.
CREATE TABLE conversation_lease (
    conversation_id varchar(36) PRIMARY KEY,
    turn_id         varchar(36) NOT NULL,
    expires_at      timestamptz NOT NULL
);

-- Which corpus versions have been imported completely. Readiness reads this, so a fresh
-- install serves no retrieval until the importer has finished.
CREATE TABLE corpus_import (
    corpus_version  text        PRIMARY KEY,
    document_count  integer     NOT NULL,
    completed_at    timestamptz NOT NULL
);
