CREATE TABLE IF NOT EXISTS event_outbox (
  id              BIGSERIAL PRIMARY KEY,
  global_sequence BIGINT NOT NULL UNIQUE,
  published_at    TIMESTAMPTZ
);
--;;
CREATE INDEX idx_event_outbox_unpublished
  ON event_outbox (id) WHERE published_at IS NULL;
