-- Tenants (ADR 002). Everything a customer owns gets the customer's id; what exists today
-- becomes the `default` tenant's, in place, so no id changes and nothing is rewritten.
CREATE TABLE tenant (
    tenant_id   varchar(36) PRIMARY KEY,
    name        text        NOT NULL,
    enabled     boolean     NOT NULL DEFAULT true,
    created_at  timestamptz NOT NULL
);
INSERT INTO tenant (tenant_id, name, enabled, created_at) VALUES ('default', 'Default tenant', true, now());

-- A key is shown once and stored hashed; the first eight characters are its id, so a
-- presented key is looked up by prefix and compared by hash.
CREATE TABLE tenant_api_key (
    key_id      varchar(8)  PRIMARY KEY,
    tenant_id   varchar(36) NOT NULL REFERENCES tenant (tenant_id),
    key_hash    char(64)    NOT NULL,
    label       text,
    created_at  timestamptz NOT NULL,
    revoked_at  timestamptz
);
CREATE INDEX tenant_api_key_tenant ON tenant_api_key (tenant_id);

-- The client's conversation id is scoped to its tenant here and mapped to the id every
-- other table keys on. Existing conversations keep their id as both: they are the default
-- tenant's and were unique already. A new conversation gets a UUID as the internal id, and
-- the external id the client sent, or the same UUID if it sent none.
CREATE TABLE conversation (
    id           varchar(36) PRIMARY KEY,
    tenant_id    varchar(36) NOT NULL REFERENCES tenant (tenant_id),
    external_id  varchar(36) NOT NULL,
    created_at   timestamptz NOT NULL,
    CONSTRAINT conversation_external UNIQUE (tenant_id, external_id)
);
INSERT INTO conversation (id, tenant_id, external_id, created_at)
SELECT ids.id, 'default', ids.id, coalesce(min(ct.started_at), now())
FROM (
    SELECT DISTINCT conversation_id AS id FROM conversation_turn
    UNION SELECT DISTINCT conversation_id FROM spring_ai_chat_memory
    UNION SELECT DISTINCT conversation_id FROM support_ticket
    UNION SELECT DISTINCT conversation_id FROM conversation_ticket_guard
    UNION SELECT DISTINCT conversation_id FROM conversation_budget
    UNION SELECT DISTINCT conversation_id FROM conversation_lease
    UNION SELECT DISTINCT conversation_id FROM answer_feedback
) ids
LEFT JOIN conversation_turn ct ON ct.conversation_id = ids.id
GROUP BY ids.id;

-- The tenant on the rows the admin scopes by and the seams carry. Budget and lease key on
-- the internal conversation id, which is scoped already, and stay as they are.
ALTER TABLE conversation_turn         ADD COLUMN tenant_id varchar(36) NOT NULL DEFAULT 'default' REFERENCES tenant (tenant_id);
ALTER TABLE answer_feedback           ADD COLUMN tenant_id varchar(36) NOT NULL DEFAULT 'default' REFERENCES tenant (tenant_id);
ALTER TABLE support_ticket            ADD COLUMN tenant_id varchar(36) NOT NULL DEFAULT 'default' REFERENCES tenant (tenant_id);
ALTER TABLE conversation_ticket_guard ADD COLUMN tenant_id varchar(36) NOT NULL DEFAULT 'default' REFERENCES tenant (tenant_id);
ALTER TABLE ticket_operation          ADD COLUMN tenant_id varchar(36) NOT NULL DEFAULT 'default' REFERENCES tenant (tenant_id);
ALTER TABLE conversation_turn         ALTER COLUMN tenant_id DROP DEFAULT;
ALTER TABLE answer_feedback           ALTER COLUMN tenant_id DROP DEFAULT;
ALTER TABLE support_ticket            ALTER COLUMN tenant_id DROP DEFAULT;
ALTER TABLE conversation_ticket_guard ALTER COLUMN tenant_id DROP DEFAULT;
ALTER TABLE ticket_operation          ALTER COLUMN tenant_id DROP DEFAULT;
CREATE INDEX conversation_turn_tenant_started ON conversation_turn (tenant_id, started_at DESC);
CREATE INDEX support_ticket_tenant_state ON support_ticket (tenant_id, state, updated_at DESC);

-- Creating a tenant, issuing or revoking a key, and switching a tenant off are recorded in
-- admin_audit: issuing a key is handing out access to a customer's conversations.
ALTER TABLE admin_audit DROP CONSTRAINT admin_audit_action_check;
ALTER TABLE admin_audit ADD CONSTRAINT admin_audit_action_check
    CHECK (action IN ('viewed_conversation', 'refused', 'published', 'rolled_back',
                      'account_disabled', 'account_enabled', 'role_changed', 'password_reset',
                      'password_changed',
                      'tenant_created', 'tenant_enabled', 'tenant_disabled', 'key_issued', 'key_revoked'));
