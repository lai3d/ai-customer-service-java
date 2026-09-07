-- A second kind of connector: an Xboard (V2board family) panel, the subscription system
-- behind a VPN reseller. It has no shop domain but a base URL, and the customer's own
-- panel token does the reading, so an admin token is optional (kept for tickets and
-- knowledge later).
ALTER TABLE order_connector DROP CONSTRAINT order_connector_kind_check;
ALTER TABLE order_connector ADD CONSTRAINT order_connector_kind_check CHECK (kind IN ('shopify', 'xboard'));
ALTER TABLE order_connector ADD COLUMN base_url text;
ALTER TABLE order_connector ALTER COLUMN access_token DROP NOT NULL;
ALTER TABLE order_connector ALTER COLUMN shop_domain DROP NOT NULL;
ALTER TABLE order_connector ADD CONSTRAINT order_connector_shape CHECK (
    (kind = 'shopify' AND shop_domain IS NOT NULL AND access_token IS NOT NULL)
 OR (kind = 'xboard' AND base_url IS NOT NULL));
