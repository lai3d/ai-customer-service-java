-- Staff belong to a tenant (ADR 002 step 5). NULL is platform staff: the people who run the
-- deployment, create tenants and issue keys, and who by the schema are admins, since a
-- platform account with nothing to administer has no reason to exist. Every account that
-- exists today becomes platform: that is what it effectively was, and the seeded first admin
-- must keep doing everything after the upgrade. A tenant's own staff are created afterwards,
-- from the Staff page, by a platform admin naming the tenant.
ALTER TABLE staff_account ADD COLUMN tenant_id varchar(36) NULL REFERENCES tenant (tenant_id);
ALTER TABLE staff_account ADD CONSTRAINT staff_account_platform_is_admin
    CHECK (tenant_id IS NOT NULL OR role = 'admin');
-- The last-enabled-admin rule counts within one tenant, or within platform.
CREATE INDEX staff_account_scope ON staff_account (tenant_id, role, enabled);
