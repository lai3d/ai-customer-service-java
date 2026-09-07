-- Business plan step 2, second half: a tenant's real order system behind OrderLookup. One
-- connector per tenant; a tenant without one has no orders to look up (the default tenant
-- keeps the bundled mock, which is what the demo and the tests are about). The access token
-- is a secret at rest: encrypted with ORDER_CONNECTOR_KEY when one is set (prefix enc:),
-- stored as given with a startup warning when not (prefix plain:).
CREATE TABLE order_connector (
    tenant_id      varchar(36) PRIMARY KEY REFERENCES tenant (tenant_id),
    kind           varchar(16) NOT NULL CHECK (kind IN ('shopify')),
    shop_domain    text        NOT NULL,
    access_token   text        NOT NULL,
    api_version    varchar(16) NOT NULL,
    configured_at  timestamptz NOT NULL,
    configured_by  varchar(64) NOT NULL
);

ALTER TABLE admin_audit DROP CONSTRAINT admin_audit_action_check;
ALTER TABLE admin_audit ADD CONSTRAINT admin_audit_action_check
    CHECK (action IN ('viewed_conversation', 'refused', 'published', 'rolled_back',
                      'account_disabled', 'account_enabled', 'role_changed', 'password_reset',
                      'password_changed',
                      'tenant_created', 'tenant_enabled', 'tenant_disabled', 'key_issued', 'key_revoked',
                      'imported', 'evaluated', 'connector_changed'));
