CREATE TABLE IF NOT EXISTS consumer_inbox (
  consumer_group  TEXT   NOT NULL,
  global_sequence BIGINT NOT NULL,
  processed_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  PRIMARY KEY (consumer_group, global_sequence)
);
--;;
CREATE TABLE IF NOT EXISTS consumer_checkpoints (
  consumer_group       TEXT PRIMARY KEY,
  last_global_sequence BIGINT NOT NULL DEFAULT 0,
  updated_at           TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
--;;
CREATE TABLE IF NOT EXISTS notifications (
  id                    BIGSERIAL PRIMARY KEY,
  stream_id             TEXT NOT NULL UNIQUE,
  notification_type     TEXT NOT NULL,
  recipient_id          TEXT NOT NULL,
  payload               JSONB NOT NULL,
  status                TEXT NOT NULL DEFAULT 'pending',
  retry_count           INT NOT NULL DEFAULT 0,
  failure_reason        TEXT,
  last_global_sequence  BIGINT NOT NULL,
  created_at            TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at            TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
--;;
CREATE TABLE IF NOT EXISTS projection_checkpoints (
  projection_name      TEXT PRIMARY KEY,
  last_global_sequence BIGINT NOT NULL DEFAULT 0,
  updated_at           TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
