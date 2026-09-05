-- The operational record of a turn: what a customer asked, what happened, how it ended.
-- Chat memory (spring_ai_chat_memory) is the model's context window -- windowed, swept and
-- not a record of outcomes -- and the SSE stream is gone the moment the customer's browser
-- closes it. This is what the operations admin reads instead, written at the service boundary
-- by the chat side, on both the blocking and the streaming path.
--
-- The row is inserted as `running` before the model is called; a turn that cannot write its
-- first row does not call the model. It is finished on every terminal signal. A row still
-- `running` past the turn lease belongs to a process that died mid-turn and is marked
-- `unknown` by the sweeper: never `completed`, because nothing knows that it did.
CREATE TABLE conversation_turn (
    turn_id         varchar(36)  PRIMARY KEY,
    conversation_id varchar(36)  NOT NULL,
    path            varchar(8)   NOT NULL CHECK (path IN ('blocking', 'stream')),
    started_at      timestamptz  NOT NULL,
    ended_at        timestamptz,
    outcome         varchar(12)  NOT NULL DEFAULT 'running'
        CHECK (outcome IN ('running', 'completed', 'failed', 'interrupted', 'unknown')),
    -- The exception's class and message on failure; staff-facing, never customer-facing.
    failure         text,
    model           varchar(80),
    input_tokens    integer,
    output_tokens   integer,
    trace_id        varchar(64),
    -- Snapshots, not references: memory is windowed, and an operational record has to
    -- outlive it. Customer text, so this table is subject to the same retention decision as
    -- memory when there is one; nothing here is exported to logs, metrics or spans.
    question        text         NOT NULL,
    answer          text
);

CREATE INDEX conversation_turn_conversation ON conversation_turn (conversation_id, started_at);
CREATE INDEX conversation_turn_started ON conversation_turn (started_at DESC);
CREATE INDEX conversation_turn_running ON conversation_turn (started_at) WHERE outcome = 'running';

-- What retrieval found for a turn, in rank order, with the score. Written before the model
-- is called, so a failed turn still shows what it was grounded on.
CREATE TABLE turn_retrieval (
    turn_id   varchar(36)      NOT NULL REFERENCES conversation_turn (turn_id),
    rank      integer          NOT NULL,
    entry_id  text             NOT NULL,
    language  varchar(8),
    score     double precision NOT NULL,
    PRIMARY KEY (turn_id, rank)
);

-- Every tool the model called during a turn and how the call came out, in order.
CREATE TABLE turn_tool_call (
    id          bigserial   PRIMARY KEY,
    turn_id     varchar(36) NOT NULL REFERENCES conversation_turn (turn_id),
    tool        varchar(64) NOT NULL,
    outcome     varchar(32) NOT NULL,
    occurred_at timestamptz NOT NULL
);

CREATE INDEX turn_tool_call_turn ON turn_tool_call (turn_id, id);
