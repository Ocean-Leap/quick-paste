-- quick-paste V1 schema, PostgreSQL (SPEC §8.1). All ids are ULIDs (CHAR(26));
-- hashes are SHA-256 hex. Timestamps are timestamptz(3) — absolute instants,
-- immune to session-timezone drift. Enums are VARCHAR + CHECK so repository
-- queries stay plain string comparisons. files.created_by enforces the
-- per-user storage quota (FR-43).
--
-- No pgvector here: the `vector` extension is created by the v3 Library
-- migration, right before the column that needs it. Keeping it out of V1 lets
-- the server boot on a plain postgres:17 or a managed instance with a
-- non-superuser app role (CREATE EXTENSION needs elevated privileges).

CREATE TABLE users (
    id            CHAR(26)     NOT NULL PRIMARY KEY,
    email         VARCHAR(255) NOT NULL,
    google_sub    VARCHAR(255) NULL,
    display_name  VARCHAR(255) NOT NULL DEFAULT '',
    is_admin      BOOLEAN      NOT NULL DEFAULT FALSE,
    status        VARCHAR(16)  NOT NULL DEFAULT 'active' CHECK (status IN ('active','disabled')),
    created_at    TIMESTAMPTZ(3) NOT NULL,
    last_login_at TIMESTAMPTZ(3) NULL,
    CONSTRAINT uq_users_email UNIQUE (email),
    CONSTRAINT uq_users_google_sub UNIQUE (google_sub)
);

CREATE TABLE channels (
    id         CHAR(26)    NOT NULL PRIMARY KEY,
    name       VARCHAR(64) NOT NULL,
    type       VARCHAR(16) NOT NULL CHECK (type IN ('private','shared')),
    created_by CHAR(26)    NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ(3) NOT NULL
);
CREATE INDEX idx_channels_created_by ON channels (created_by);

CREATE TABLE channel_members (
    id            CHAR(26)     NOT NULL PRIMARY KEY,
    channel_id    CHAR(26)     NOT NULL REFERENCES channels (id) ON DELETE CASCADE,
    user_id       CHAR(26)     NULL REFERENCES users (id) ON DELETE CASCADE,
    invited_email VARCHAR(255) NULL,
    role          VARCHAR(16)  NOT NULL CHECK (role IN ('owner','member')),
    status        VARCHAR(16)  NOT NULL CHECK (status IN ('pending','active')),
    joined_at     TIMESTAMPTZ(3) NULL,
    CONSTRAINT uq_members_channel_user UNIQUE (channel_id, user_id),
    CONSTRAINT uq_members_channel_email UNIQUE (channel_id, invited_email)
);
CREATE INDEX idx_members_user ON channel_members (user_id);
CREATE INDEX idx_members_email ON channel_members (invited_email);

CREATE TABLE api_tokens (
    id           CHAR(26)    NOT NULL PRIMARY KEY,
    user_id      CHAR(26)    NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    name         VARCHAR(64) NOT NULL,
    token_hash   CHAR(64)    NOT NULL,
    prefix       VARCHAR(12) NOT NULL,
    scope        VARCHAR(8)  NOT NULL DEFAULT 'write' CHECK (scope IN ('read','write')),
    created_at   TIMESTAMPTZ(3) NOT NULL,
    last_used_at TIMESTAMPTZ(3) NULL,
    expires_at   TIMESTAMPTZ(3) NULL,
    revoked_at   TIMESTAMPTZ(3) NULL
);
CREATE INDEX idx_tokens_user ON api_tokens (user_id);
CREATE INDEX idx_tokens_prefix ON api_tokens (prefix);

CREATE TABLE sessions (
    id         CHAR(26)     NOT NULL PRIMARY KEY,
    user_id    CHAR(26)     NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ(3) NOT NULL,
    expires_at TIMESTAMPTZ(3) NOT NULL,
    user_agent VARCHAR(255) NOT NULL DEFAULT '',
    revoked_at TIMESTAMPTZ(3) NULL
);
CREATE INDEX idx_sessions_user ON sessions (user_id);
CREATE INDEX idx_sessions_expires ON sessions (expires_at);

CREATE TABLE files (
    id           CHAR(26)     NOT NULL PRIMARY KEY,
    channel_id   CHAR(26)     NOT NULL REFERENCES channels (id) ON DELETE CASCADE,
    created_by   CHAR(26)     NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    name         VARCHAR(255) NOT NULL,
    mime         VARCHAR(128) NOT NULL,
    size         BIGINT       NOT NULL,
    storage_path VARCHAR(512) NOT NULL,
    checksum     CHAR(64)     NOT NULL,
    created_at   TIMESTAMPTZ(3) NOT NULL,
    expires_at   TIMESTAMPTZ(3) NOT NULL
);
CREATE INDEX idx_files_channel ON files (channel_id);
CREATE INDEX idx_files_expires ON files (expires_at);
CREATE INDEX idx_files_creator ON files (created_by);

CREATE TABLE clips (
    id             CHAR(26)    NOT NULL PRIMARY KEY,
    channel_id     CHAR(26)    NOT NULL REFERENCES channels (id) ON DELETE CASCADE,
    origin_user_id CHAR(26)    NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    origin_device  VARCHAR(64) NOT NULL,
    type           VARCHAR(8)  NOT NULL CHECK (type IN ('text','file')),
    text           TEXT        NULL,
    file_id        CHAR(26)    NULL REFERENCES files (id) ON DELETE CASCADE,
    content_hash   CHAR(64)    NOT NULL,
    encrypted      BOOLEAN     NOT NULL DEFAULT FALSE,  -- reserved for v2 E2E (SPEC §9.9)
    enc_algo       VARCHAR(32) NULL,                    -- reserved
    iv             BYTEA       NULL,                    -- reserved
    created_at     TIMESTAMPTZ(3) NOT NULL,
    expires_at     TIMESTAMPTZ(3) NOT NULL
);
CREATE INDEX idx_clips_channel_created ON clips (channel_id, created_at);
CREATE INDEX idx_clips_channel_id ON clips (channel_id, id);
CREATE INDEX idx_clips_channel_expires ON clips (channel_id, expires_at);
CREATE INDEX idx_clips_expires ON clips (expires_at);
CREATE INDEX idx_clips_dedup ON clips (channel_id, origin_device, content_hash);
CREATE INDEX idx_clips_file ON clips (file_id);

CREATE TABLE export_jobs (
    id           CHAR(26)     NOT NULL PRIMARY KEY,
    user_id      CHAR(26)     NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    status       VARCHAR(16)  NOT NULL CHECK (status IN ('pending','running','done','failed')),
    storage_path VARCHAR(512) NULL,
    created_at   TIMESTAMPTZ(3) NOT NULL,
    expires_at   TIMESTAMPTZ(3) NULL
);
CREATE INDEX idx_exports_user ON export_jobs (user_id);
CREATE INDEX idx_exports_expires ON export_jobs (expires_at);
