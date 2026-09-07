-- Business plan step 3: the numbers a customer pays for. Two of them, kept apart because
-- they measure different things.
--
-- A golden set is what the assistant should say: questions with what a correct answer must
-- contain, which entries retrieval must find, which tool must run, or that it must decline.
-- An evaluation run asks every enabled case through the real chat path and scores the
-- answers; the latest run's ratios are gauges on the dashboard, so a change to the model,
-- the prompt or top-k is measured before and after rather than felt.
--
-- Deflection is what happened with real customers: the share of conversations in a window
-- that ended without a ticket for a person. It is sampled from the tables that already
-- exist and needs only one thing here: evaluation conversations marked, so they never count
-- as customers.

ALTER TABLE conversation ADD COLUMN kind varchar(12) NOT NULL DEFAULT 'customer' CHECK (kind IN ('customer', 'evaluation'));

CREATE TABLE golden_case (
    id                  bigserial   PRIMARY KEY,
    tenant_id           varchar(36) NOT NULL REFERENCES tenant (tenant_id),
    question            text        NOT NULL,
    language            varchar(8)  NOT NULL,
    -- every one of these entry ids must be among the passages retrieved for the question
    expected_entry_ids  jsonb       NOT NULL DEFAULT '[]',
    -- every one of these phrases must appear in the answer, case-insensitively
    must_contain        jsonb       NOT NULL DEFAULT '[]',
    -- at least one of these must appear; empty means no such requirement
    any_of              jsonb       NOT NULL DEFAULT '[]',
    -- none of these may appear
    must_not_contain    jsonb       NOT NULL DEFAULT '[]',
    -- a tool that must have run during the turn, by its name, or null
    expect_tool         varchar(64),
    -- the question is outside the knowledge; a correct answer declines rather than invents
    expect_refusal      boolean     NOT NULL DEFAULT false,
    enabled             boolean     NOT NULL DEFAULT true,
    note                text,
    created_at          timestamptz NOT NULL,
    created_by          varchar(64) NOT NULL
);
CREATE INDEX golden_case_tenant ON golden_case (tenant_id, id);

CREATE TABLE evaluation_run (
    id              bigserial   PRIMARY KEY,
    tenant_id       varchar(36) NOT NULL REFERENCES tenant (tenant_id),
    state           varchar(12) NOT NULL CHECK (state IN ('running', 'done', 'failed')),
    cases           integer     NOT NULL DEFAULT 0,
    passed          integer     NOT NULL DEFAULT 0,
    retrieval_hits  integer     NOT NULL DEFAULT 0,
    answer_passes   integer     NOT NULL DEFAULT 0,
    tool_passes     integer     NOT NULL DEFAULT 0,
    input_tokens    bigint      NOT NULL DEFAULT 0,
    output_tokens   bigint      NOT NULL DEFAULT 0,
    model           text,
    note            text,
    error           text,
    requested_by    varchar(64) NOT NULL,
    started_at      timestamptz NOT NULL,
    finished_at     timestamptz
);
CREATE INDEX evaluation_run_tenant ON evaluation_run (tenant_id, started_at DESC);

CREATE TABLE evaluation_result (
    run_id          bigint      NOT NULL REFERENCES evaluation_run (id) ON DELETE CASCADE,
    case_id         bigint      NOT NULL,
    question        text        NOT NULL,
    conversation_id varchar(36),
    retrieved       jsonb       NOT NULL DEFAULT '[]',
    tools           jsonb       NOT NULL DEFAULT '[]',
    answer          text,
    retrieval_hit   boolean     NOT NULL,
    answer_pass     boolean     NOT NULL,
    tool_pass       boolean     NOT NULL,
    passed          boolean     NOT NULL,
    failures        text,
    input_tokens    integer,
    output_tokens   integer,
    millis          bigint,
    PRIMARY KEY (run_id, case_id)
);

ALTER TABLE admin_audit DROP CONSTRAINT admin_audit_action_check;
ALTER TABLE admin_audit ADD CONSTRAINT admin_audit_action_check
    CHECK (action IN ('viewed_conversation', 'refused', 'published', 'rolled_back',
                      'account_disabled', 'account_enabled', 'role_changed', 'password_reset',
                      'password_changed',
                      'tenant_created', 'tenant_enabled', 'tenant_disabled', 'key_issued', 'key_revoked',
                      'imported', 'evaluated'));
