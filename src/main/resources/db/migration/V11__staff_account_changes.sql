-- Account management: disabling, a role change and a password reset are recorded in
-- admin_audit, the target being the username. staff_account itself is unchanged: enabled
-- and role were there from V4, and a password reset overwrites the hash.
ALTER TABLE admin_audit DROP CONSTRAINT admin_audit_action_check;
ALTER TABLE admin_audit ADD CONSTRAINT admin_audit_action_check
    CHECK (action IN ('viewed_conversation', 'refused', 'published', 'rolled_back',
                      'account_disabled', 'account_enabled', 'role_changed', 'password_reset'));
