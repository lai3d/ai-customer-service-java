-- What staff did in the admin that is not a change to a ticket: opening a customer
-- conversation, and being refused. Both are recorded on purpose.
--
-- The admin is the one surface in this system that shows customer text deliberately; the
-- rest of it keeps that text out of spans and logs. A page that opens that door should say
-- who used it, so a view is an action here, one row per conversation opened.
--
-- A refusal -- a rule the workflow would not bend, a role the server would not honour -- is
-- recorded because an audit trail that holds only what succeeded is missing exactly the rows
-- an investigation would open it for. ticket_event stays what it is: only real changes.
-- Append-only; nothing in the application updates or deletes a row.
CREATE TABLE admin_audit (
    id          bigserial   PRIMARY KEY,
    actor       varchar(64) NOT NULL,
    action      varchar(32) NOT NULL CHECK (action IN ('viewed_conversation', 'refused')),
    target      varchar(64) NOT NULL,
    detail      text,
    occurred_at timestamptz NOT NULL
);

CREATE INDEX admin_audit_target ON admin_audit (target, id);
CREATE INDEX admin_audit_actor ON admin_audit (actor, id);
