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
  balance               BIGINT       NOT NULL DEFAULT 0,
  last_global_sequence  BIGINT       NOT NULL DEFAULT 0,
  updated_at            TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
--;;
CREATE TABLE IF NOT EXISTS projection_checkpoints (
  projection_name       TEXT         PRIMARY KEY,
  last_global_sequence  BIGINT       NOT NULL DEFAULT 0
);
