-- Staff changing their own password is recorded in admin_audit as password_changed, the
-- target being the username; an admin's reset of someone's password stays password_reset.
-- A wrong current password is a refusal, recorded like every other refusal.
ALTER TABLE admin_audit DROP CONSTRAINT admin_audit_action_check;
ALTER TABLE admin_audit ADD CONSTRAINT admin_audit_action_check
    CHECK (action IN ('viewed_conversation', 'refused', 'published', 'rolled_back',
                      'account_disabled', 'account_enabled', 'role_changed', 'password_reset',
                      'password_changed'));
