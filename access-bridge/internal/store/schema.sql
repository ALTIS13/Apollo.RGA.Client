CREATE TABLE IF NOT EXISTS bridge_subjects (
    panel_instance VARCHAR(64) NOT NULL,
    user_id BIGINT NOT NULL CHECK (user_id > 0),
    olcrtc_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    policy_revision BIGINT NOT NULL DEFAULT 1 CHECK (policy_revision > 0),
    PRIMARY KEY (panel_instance, user_id)
);

-- migration-step
CREATE TABLE IF NOT EXISTS bridge_installations (
    installation_id VARCHAR(128) PRIMARY KEY,
    panel_instance VARCHAR(64) NOT NULL,
    user_id BIGINT NOT NULL,
    public_key_sha256 BYTEA NOT NULL CHECK (octet_length(public_key_sha256) = 32),
    FOREIGN KEY (panel_instance, user_id)
        REFERENCES bridge_subjects (panel_instance, user_id)
        ON DELETE CASCADE
);

-- migration-step
CREATE TABLE IF NOT EXISTS bridge_link_challenges (
    challenge_id VARCHAR(128) PRIMARY KEY,
    panel_instance VARCHAR(64) NOT NULL,
    user_id BIGINT NOT NULL CHECK (user_id > 0),
    installation_id VARCHAR(128) NOT NULL,
    public_key_sha256 BYTEA NOT NULL CHECK (octet_length(public_key_sha256) = 32),
    nonce BYTEA NOT NULL CHECK (octet_length(nonce) = 32),
    expires_at TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ
);

-- migration-step
CREATE INDEX IF NOT EXISTS bridge_link_challenges_expiry_idx
    ON bridge_link_challenges (expires_at);
