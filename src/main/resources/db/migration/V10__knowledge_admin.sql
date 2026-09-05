-- Two more things the admin's audit records: a publication and a rollback, with who and
-- which version. And the link the proposal wanted from a handled answer flag to the
-- revision that fixed it, optional, because a flag can be handled without a text change.
ALTER TABLE admin_audit DROP CONSTRAINT admin_audit_action_check;
ALTER TABLE admin_audit ADD CONSTRAINT admin_audit_action_check
    CHECK (action IN ('viewed_conversation', 'refused', 'published', 'rolled_back'));

ALTER TABLE answer_feedback ADD COLUMN revision_id bigint REFERENCES knowledge_revision (id);
