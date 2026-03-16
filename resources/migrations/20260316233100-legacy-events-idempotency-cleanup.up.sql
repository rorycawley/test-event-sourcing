ALTER TABLE events DROP CONSTRAINT IF EXISTS events_idempotency_key_key;
--;;
ALTER TABLE events DROP COLUMN IF EXISTS idempotency_key;
--;;
ALTER TABLE events ADD COLUMN IF NOT EXISTS event_version INTEGER NOT NULL DEFAULT 1;
