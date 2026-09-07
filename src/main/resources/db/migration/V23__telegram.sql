-- The Telegram channel: one bot per tenant, from BotFather, talking to the same ChatService
-- as the widget. The bot token is sealed like a connector token. A chat maps to a
-- conversation of the tenant; /new starts another, so the external id carries a counter.
CREATE TABLE telegram_bot (
    tenant_id       varchar(36) PRIMARY KEY REFERENCES tenant (tenant_id),
    bot_token       text        NOT NULL,
    bot_username    text,
    -- polling: this process asks Telegram for updates (one instance only); webhook: Telegram
    -- posts them to /telegram/{tenant}/{secret} on app.public-url (any number of replicas).
    mode            varchar(8)  NOT NULL CHECK (mode IN ('polling', 'webhook')),
    webhook_secret  varchar(64) NOT NULL,
    configured_at   timestamptz NOT NULL,
    configured_by   varchar(64) NOT NULL
);

CREATE TABLE telegram_chat (
    tenant_id       varchar(36) NOT NULL REFERENCES tenant (tenant_id),
    chat_id         bigint      NOT NULL,
    -- how many conversations this chat has had; the current one is tg-<chat>-<n>
    conversations   integer     NOT NULL DEFAULT 1,
    last_update_id  bigint,
    PRIMARY KEY (tenant_id, chat_id)
);

ALTER TABLE admin_audit DROP CONSTRAINT admin_audit_action_check;
ALTER TABLE admin_audit ADD CONSTRAINT admin_audit_action_check
    CHECK (action IN ('viewed_conversation', 'refused', 'published', 'rolled_back',
                      'account_disabled', 'account_enabled', 'role_changed', 'password_reset',
                      'password_changed',
                      'tenant_created', 'tenant_enabled', 'tenant_disabled', 'key_issued', 'key_revoked',
                      'imported', 'evaluated', 'connector_changed', 'channel_changed'));
