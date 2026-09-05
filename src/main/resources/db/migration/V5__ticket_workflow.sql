-- The human side of a ticket: who has it, where it is in its life, and everything that was
-- done to it. V2 made a ticket a row the AI could create; this makes it a case a person can
-- claim, work and close. Columns are added, not rewritten, so the tool path (V2, V3) and the
-- rows it already wrote are untouched -- every existing ticket becomes an open, unowned one.

ALTER TABLE support_ticket
    ADD COLUMN state      varchar(16) NOT NULL DEFAULT 'open'
        CHECK (state IN ('open', 'claimed', 'resolved', 'closed')),
    ADD COLUMN owner      varchar(64),
    ADD COLUMN updated_at timestamptz NOT NULL DEFAULT now(),
    -- Bumped by every change. A mutation carries the version it read, so two people acting on
    -- one stale page cannot both succeed, and a retried request cannot apply twice.
    ADD COLUMN version    integer     NOT NULL DEFAULT 0;

UPDATE support_ticket SET updated_at = created_at;

-- The queue reads by state and recency; a person's own list reads by owner.
CREATE INDEX support_ticket_state_updated ON support_ticket (state, updated_at DESC);
CREATE INDEX support_ticket_owner ON support_ticket (owner) WHERE owner IS NOT NULL;

-- Everything done to a ticket after the AI created it, in order, each with who and when.
-- Creation itself is not a row here: the ticket's own created_at and the operation record
-- in ticket_operation already say when and how it came to exist. Append-only; nothing
-- updates or deletes a row, and the admin never exposes a way to.
CREATE TABLE ticket_event (
    id            bigserial   PRIMARY KEY,
    ticket_number varchar(20) NOT NULL REFERENCES support_ticket (ticket_number),
    kind          varchar(16) NOT NULL
        CHECK (kind IN ('claimed', 'assigned', 'released', 'resolved', 'closed', 'reopened', 'note')),
    actor         varchar(64) NOT NULL,
    from_state    varchar(16),
    to_state      varchar(16),
    from_owner    varchar(64),
    to_owner      varchar(64),
    -- A note's text, or a resolution's conclusion. The conclusion is here and not on the
    -- ticket row so that reopening carries nothing forward and every conclusion a ticket
    -- ever had stays in its history.
    note          text,
    occurred_at   timestamptz NOT NULL
);

CREATE INDEX ticket_event_ticket ON ticket_event (ticket_number, id);
