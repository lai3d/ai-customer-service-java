-- Xboard, steps 2 and 3: tickets raised in the panel as the customer, and the panel's
-- knowledge articles as an import source. The admin API of a panel lives under a
-- per-panel secret path segment, kept with the connector next to the admin token.
ALTER TABLE order_connector ADD COLUMN admin_path text;

ALTER TABLE knowledge_import DROP CONSTRAINT knowledge_import_source_kind_check;
ALTER TABLE knowledge_import ADD CONSTRAINT knowledge_import_source_kind_check CHECK (source_kind IN ('url', 'pdf', 'xboard'));
ALTER TABLE knowledge_entry DROP CONSTRAINT knowledge_entry_source_kind_check;
ALTER TABLE knowledge_entry ADD CONSTRAINT knowledge_entry_source_kind_check CHECK (source_kind IN ('url', 'pdf', 'xboard'));
