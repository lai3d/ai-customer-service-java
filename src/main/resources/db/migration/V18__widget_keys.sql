-- Two kinds of API key. A secret key is for a server: it is hashed, shown once, and sent
-- from code the tenant controls. A widget key is for a browser: it sits in the tenant's web
-- page for anyone to read, so what bounds it is not secrecy but the origins it may be used
-- from -- the filter refuses a browser request whose Origin is not on the key's list and
-- answers CORS only for those. Comma-separated origins, scheme://host[:port], no path.
ALTER TABLE tenant_api_key ADD COLUMN kind varchar(8) NOT NULL DEFAULT 'secret' CHECK (kind IN ('secret', 'widget'));
ALTER TABLE tenant_api_key ADD COLUMN origins text;
ALTER TABLE tenant_api_key ADD CONSTRAINT tenant_api_key_widget_origins
    CHECK (kind = 'secret' OR (origins IS NOT NULL AND origins <> ''));
