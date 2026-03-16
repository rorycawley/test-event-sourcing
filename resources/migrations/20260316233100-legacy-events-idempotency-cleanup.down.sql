ALTER TABLE events DROP COLUMN IF EXISTS event_version;
--;;
ALTER TABLE events ADD COLUMN IF NOT EXISTS idempotency_key TEXT;
--;;
ALTER TABLE events
  ADD CONSTRAINT events_idempotency_key_key
  UNIQUE (idempotency_key);
