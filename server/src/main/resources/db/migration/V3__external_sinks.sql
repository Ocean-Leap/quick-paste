-- External sink channels (SPEC §5.14 / §8.1): type=external + Steward Mind
-- outbound config + per-clip delivery ledger.

ALTER TABLE channels DROP CONSTRAINT IF EXISTS channels_type_check;
ALTER TABLE channels ADD CONSTRAINT channels_type_check
    CHECK (type IN ('private', 'shared', 'external'));

CREATE TABLE channel_sinks (
    channel_id       CHAR(26)     NOT NULL PRIMARY KEY REFERENCES channels (id) ON DELETE CASCADE,
    provider         VARCHAR(32)  NOT NULL CHECK (provider IN ('steward')),
    base_url         VARCHAR(512) NOT NULL,
    token_ciphertext TEXT         NOT NULL,
    token_prefix     VARCHAR(16)  NOT NULL,
    default_tags     TEXT         NULL,
    created_at       TIMESTAMPTZ(3) NOT NULL,
    updated_at       TIMESTAMPTZ(3) NOT NULL
);

CREATE TABLE clip_deliveries (
    clip_id           CHAR(26)    NOT NULL PRIMARY KEY REFERENCES clips (id) ON DELETE CASCADE,
    channel_id        CHAR(26)    NOT NULL REFERENCES channels (id) ON DELETE CASCADE,
    status            VARCHAR(16) NOT NULL CHECK (status IN ('pending', 'sent', 'deduped', 'failed')),
    kind              VARCHAR(16) NULL CHECK (kind IS NULL OR kind IN ('note', 'quote', 'link')),
    external_item_id  VARCHAR(64) NULL,
    attempts          INT         NOT NULL DEFAULT 0,
    next_attempt_at   TIMESTAMPTZ(3) NOT NULL,
    last_error        VARCHAR(512) NULL,
    updated_at        TIMESTAMPTZ(3) NOT NULL
);
CREATE INDEX idx_clip_deliveries_due
    ON clip_deliveries (next_attempt_at)
    WHERE status IN ('pending', 'failed');
CREATE INDEX idx_clip_deliveries_channel ON clip_deliveries (channel_id);
