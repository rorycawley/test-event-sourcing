CREATE TABLE IF NOT EXISTS events (
  id                UUID         PRIMARY KEY,
  global_sequence   BIGSERIAL    NOT NULL UNIQUE,
  stream_id         TEXT         NOT NULL,
  stream_sequence   BIGINT       NOT NULL,
  event_type        TEXT         NOT NULL,
  event_version     INTEGER      NOT NULL DEFAULT 1,
  payload           JSONB        NOT NULL,
  created_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
  UNIQUE (stream_id, stream_sequence)
);
--;;
CREATE TABLE IF NOT EXISTS idempotency_keys (
  idempotency_key   TEXT         PRIMARY KEY,
  stream_id         TEXT         NOT NULL,
  command_type      TEXT,
  command_payload   JSONB        NOT NULL DEFAULT '{}'::jsonb,
  created_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
--;;
CREATE TABLE IF NOT EXISTS account_balances (
  account_id            TEXT         PRIMARY KEY,
  owner                 TEXT         NOT NULL DEFAULT '',
  balance               BIGINT       NOT NULL DEFAULT 0,
  last_global_sequence  BIGINT       NOT NULL DEFAULT 0,
  updated_at            TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
--;;
CREATE TABLE IF NOT EXISTS projection_checkpoints (
  projection_name       TEXT         PRIMARY KEY,
  last_global_sequence  BIGINT       NOT NULL DEFAULT 0
);
--;;
CREATE TABLE IF NOT EXISTS transfer_status (
  transfer_id           TEXT         PRIMARY KEY,
  from_account          TEXT         NOT NULL,
  to_account            TEXT         NOT NULL,
  amount                BIGINT       NOT NULL,
  status                TEXT         NOT NULL DEFAULT 'initiated',
  failure_reason        TEXT,
  last_global_sequence  BIGINT       NOT NULL DEFAULT 0,
  updated_at            TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
--;;
CREATE TABLE IF NOT EXISTS event_outbox (
  id              BIGSERIAL PRIMARY KEY,
  global_sequence BIGINT NOT NULL UNIQUE,
  message         JSONB,
  published_at    TIMESTAMPTZ
);
--;;
CREATE INDEX IF NOT EXISTS idx_event_outbox_unpublished
  ON event_outbox (id) WHERE published_at IS NULL;
