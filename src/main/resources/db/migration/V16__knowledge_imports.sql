-- A tenant's own documents (ADR 002, step 4): a web page or a PDF is fetched and parsed on
-- the knowledge role, split into chunks, and each chunk becomes a draft entry whose id is
-- derived from the source and the chunk's position, so importing the same source again
-- replaces its drafts rather than adding beside them. Nothing an import does is live until
-- an admin publishes; that stays the explicit step it always was.
CREATE TABLE knowledge_import (
    id            bigserial   PRIMARY KEY,
    tenant_id     varchar(36) NOT NULL REFERENCES tenant (tenant_id),
    source_kind   varchar(8)  NOT NULL CHECK (source_kind IN ('url', 'pdf')),
    source        text        NOT NULL,
    -- running: fetching, parsing, writing drafts; done: drafts written, count in entries;
    -- failed: nothing written, reason in error.
    state         varchar(12) NOT NULL CHECK (state IN ('running', 'done', 'failed')),
    entries       integer,
    error         text,
    requested_by  varchar(64) NOT NULL,
    requested_at  timestamptz NOT NULL,
    finished_at   timestamptz
);
CREATE INDEX knowledge_import_tenant ON knowledge_import (tenant_id, requested_at DESC);

-- Where an entry came from, for the admin's list and for replacement on re-import; null for
-- entries people typed and for the bundled corpus.
ALTER TABLE knowledge_entry ADD COLUMN source_kind varchar(8) CHECK (source_kind IN ('url', 'pdf'));
ALTER TABLE knowledge_entry ADD COLUMN source text;
CREATE INDEX knowledge_entry_source ON knowledge_entry (tenant_id, source) WHERE source IS NOT NULL;

ALTER TABLE admin_audit DROP CONSTRAINT admin_audit_action_check;
ALTER TABLE admin_audit ADD CONSTRAINT admin_audit_action_check
    CHECK (action IN ('viewed_conversation', 'refused', 'published', 'rolled_back',
                      'account_disabled', 'account_enabled', 'role_changed', 'password_reset',
                      'password_changed',
                      'tenant_created', 'tenant_enabled', 'tenant_disabled', 'key_issued', 'key_revoked',
                      'imported'));
