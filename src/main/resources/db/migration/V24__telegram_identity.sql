-- A Telegram chat identified through the tenant's panel: the panel user the Telegram
-- account is bound to, cached once found so a turn costs no extra call; cleared by /new.
ALTER TABLE telegram_chat ADD COLUMN panel_user_id bigint;
