-- One row per attempt to create a ticket, keyed by the operation id the chat side generated.
-- A retry after an ambiguous timeout reads its own result back instead of writing twice; the
-- fingerprint catches an id reused with different input. Recorded in the same transaction as
-- the ticket, so there is no window in which one exists without the other.
CREATE TABLE ticket_operation (
    operation_id    varchar(36) PRIMARY KEY,
    conversation_id varchar(36) NOT NULL,
    fingerprint     text        NOT NULL,
    status          varchar(12) NOT NULL,
    ticket_number   varchar(20),
    explanation     text,
    created_at      timestamptz NOT NULL
);
