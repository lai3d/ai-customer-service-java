-- A flag on one recorded turn: staff read an answer, found it wrong, incomplete or unhelpful,
-- and said so. Handling it is a conclusion -- what was done, or why nothing was -- not a
-- claim that the customer's problem is solved; that is the ticket's job. Bound to the turn,
-- so the flag survives whatever the knowledge base later becomes: the turn's retrieval rows
-- say what the answer was grounded on at the time.
CREATE TABLE answer_feedback (
    id              bigserial   PRIMARY KEY,
    turn_id         varchar(36) NOT NULL REFERENCES conversation_turn (turn_id),
    conversation_id varchar(36) NOT NULL,
    issue           varchar(16) NOT NULL CHECK (issue IN ('incorrect', 'incomplete', 'unhelpful', 'other')),
    note            text,
    state           varchar(12) NOT NULL DEFAULT 'open' CHECK (state IN ('open', 'handled', 'dismissed')),
    conclusion      text,
    reported_by     varchar(64) NOT NULL,
    reported_at     timestamptz NOT NULL,
    handled_by      varchar(64),
    handled_at      timestamptz,
    -- Same rule as tickets: a mutation carries the version it read.
    version         integer     NOT NULL DEFAULT 0
);

CREATE INDEX answer_feedback_state ON answer_feedback (state, reported_at DESC);
CREATE INDEX answer_feedback_turn ON answer_feedback (turn_id);
CREATE INDEX answer_feedback_conversation ON answer_feedback (conversation_id);
