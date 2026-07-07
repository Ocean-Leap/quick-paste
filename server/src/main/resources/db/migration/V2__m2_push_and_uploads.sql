-- M2/M3: web-push subscriptions (FR-38) and resumable upload sessions (SPEC §8.6).

CREATE TABLE push_subscriptions (
    id         CHAR(26)     NOT NULL PRIMARY KEY,
    user_id    CHAR(26)     NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    endpoint   VARCHAR(512) NOT NULL,
    p256dh     VARCHAR(255) NOT NULL,
    auth       VARCHAR(255) NOT NULL,
    device     VARCHAR(64)  NOT NULL,
    created_at TIMESTAMPTZ(3) NOT NULL,
    CONSTRAINT uq_push_endpoint UNIQUE (endpoint)
);
CREATE INDEX idx_push_user ON push_subscriptions (user_id);

-- One row per in-flight resumable upload; the session id becomes the fileId on
-- finalize. The current offset is the size of the .part file on disk (survives
-- restarts).
CREATE TABLE upload_sessions (
    id            CHAR(26)     NOT NULL PRIMARY KEY,
    channel_id    CHAR(26)     NOT NULL REFERENCES channels (id) ON DELETE CASCADE,
    created_by    CHAR(26)     NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    name          VARCHAR(255) NOT NULL,
    mime          VARCHAR(128) NOT NULL,
    declared_size BIGINT       NOT NULL,
    created_at    TIMESTAMPTZ(3) NOT NULL,
    updated_at    TIMESTAMPTZ(3) NOT NULL
);
CREATE INDEX idx_uploads_channel ON upload_sessions (channel_id);
CREATE INDEX idx_uploads_updated ON upload_sessions (updated_at);
