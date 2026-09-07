-- Knowledge per tenant (ADR 002, step 3). Every entry, revision and version belongs to a
-- tenant; an entry id is unique within its tenant, not across them, so two customers can
-- both have a 'shipping-cost'. What retrieval reads is the tenant's own active version:
-- knowledge_active becomes one row per tenant instead of the single row id = 1. Everything
-- that exists today is the default tenant's.

ALTER TABLE knowledge_entry    ADD COLUMN tenant_id varchar(36) NOT NULL DEFAULT 'default' REFERENCES tenant (tenant_id);
ALTER TABLE knowledge_revision ADD COLUMN tenant_id varchar(36) NOT NULL DEFAULT 'default' REFERENCES tenant (tenant_id);
ALTER TABLE knowledge_version  ADD COLUMN tenant_id varchar(36) NOT NULL DEFAULT 'default' REFERENCES tenant (tenant_id);

ALTER TABLE knowledge_revision DROP CONSTRAINT knowledge_revision_entry_id_fkey;
ALTER TABLE knowledge_entry    DROP CONSTRAINT knowledge_entry_pkey;
ALTER TABLE knowledge_entry    ADD PRIMARY KEY (tenant_id, entry_id);
ALTER TABLE knowledge_revision ADD CONSTRAINT knowledge_revision_entry_fkey
    FOREIGN KEY (tenant_id, entry_id) REFERENCES knowledge_entry (tenant_id, entry_id);

DROP INDEX knowledge_revision_one_draft;
DROP INDEX knowledge_revision_one_published;
DROP INDEX knowledge_revision_entry;
CREATE UNIQUE INDEX knowledge_revision_one_draft     ON knowledge_revision (tenant_id, entry_id, language) WHERE state = 'draft';
CREATE UNIQUE INDEX knowledge_revision_one_published ON knowledge_revision (tenant_id, entry_id, language) WHERE state = 'published';
CREATE INDEX knowledge_revision_entry ON knowledge_revision (tenant_id, entry_id, language, id);
CREATE INDEX knowledge_version_tenant ON knowledge_version (tenant_id, created_at DESC);

ALTER TABLE knowledge_entry    ALTER COLUMN tenant_id DROP DEFAULT;
ALTER TABLE knowledge_revision ALTER COLUMN tenant_id DROP DEFAULT;
ALTER TABLE knowledge_version  ALTER COLUMN tenant_id DROP DEFAULT;

-- The active pointer, one row per tenant. The default tenant's row keeps the version that
-- is serving; every other tenant starts with nothing active, and so with no retrieval,
-- until it publishes. Tenants.create inserts the row for a new tenant.
ALTER TABLE knowledge_active ADD COLUMN tenant_id varchar(36) REFERENCES tenant (tenant_id);
UPDATE knowledge_active SET tenant_id = 'default' WHERE id = 1;
ALTER TABLE knowledge_active DROP CONSTRAINT knowledge_active_pkey;
ALTER TABLE knowledge_active DROP COLUMN id;
ALTER TABLE knowledge_active ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE knowledge_active ADD PRIMARY KEY (tenant_id);
INSERT INTO knowledge_active (tenant_id, version)
    SELECT tenant_id, NULL FROM tenant WHERE tenant_id <> 'default'
    ON CONFLICT (tenant_id) DO NOTHING;
