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
