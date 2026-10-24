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
-- PostgreSQL requires an explicit unique key matching the composite QA FK.
CREATE UNIQUE INDEX IF NOT EXISTS bridge_installations_subject_identity_idx
    ON bridge_installations (installation_id, panel_instance, user_id);

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

-- migration-step
CREATE TABLE IF NOT EXISTS bridge_meter_sessions (
    session_id VARCHAR(128) PRIMARY KEY,
    panel_instance VARCHAR(64) NOT NULL,
    user_id BIGINT NOT NULL,
    last_sequence BIGINT NOT NULL DEFAULT 0 CHECK (last_sequence >= 0),
    client_to_exit_bytes BIGINT NOT NULL DEFAULT 0 CHECK (client_to_exit_bytes >= 0),
    exit_to_client_bytes BIGINT NOT NULL DEFAULT 0 CHECK (exit_to_client_bytes >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    FOREIGN KEY (panel_instance, user_id)
        REFERENCES bridge_subjects (panel_instance, user_id)
        ON DELETE RESTRICT
);

-- migration-step
CREATE TABLE IF NOT EXISTS bridge_budget_cycles (
    panel_instance VARCHAR(64) NOT NULL,
    user_id BIGINT NOT NULL,
    cycle_id VARCHAR(128) NOT NULL CHECK (cycle_id <> ''),
    cycle_start TIMESTAMPTZ NOT NULL,
    cycle_end TIMESTAMPTZ NOT NULL,
    total_limit_bytes BIGINT NOT NULL CHECK (total_limit_bytes > 0),
    confirmed_xray_bytes BIGINT NOT NULL CHECK (confirmed_xray_bytes >= 0),
    xray_observed_at TIMESTAMPTZ NOT NULL,
    committed_olcrtc_bytes BIGINT NOT NULL DEFAULT 0 CHECK (committed_olcrtc_bytes >= 0),
    held_bytes BIGINT NOT NULL DEFAULT 0 CHECK (held_bytes >= 0),
    overdrawn BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY (panel_instance, user_id, cycle_id),
    CHECK (cycle_end > cycle_start),
    FOREIGN KEY (panel_instance, user_id)
        REFERENCES bridge_subjects (panel_instance, user_id)
        ON DELETE RESTRICT
);

-- migration-step
CREATE TABLE IF NOT EXISTS bridge_budget_leases (
    jti VARCHAR(128) PRIMARY KEY,
    session_id VARCHAR(128) NOT NULL UNIQUE,
    panel_instance VARCHAR(64) NOT NULL,
    user_id BIGINT NOT NULL,
    cycle_id VARCHAR(128) NOT NULL,
    installation_id VARCHAR(128) NOT NULL,
    room_id VARCHAR(128) NOT NULL CHECK (room_id <> ''),
    country CHAR(2) NOT NULL CHECK (country ~ '^[A-Z]{2}$'),
    policy_revision BIGINT NOT NULL CHECK (policy_revision > 0),
    byte_limit BIGINT NOT NULL CHECK (byte_limit > 0),
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    FOREIGN KEY (panel_instance, user_id, cycle_id)
        REFERENCES bridge_budget_cycles (panel_instance, user_id, cycle_id)
        ON DELETE RESTRICT,
    FOREIGN KEY (session_id)
        REFERENCES bridge_meter_sessions (session_id)
        ON DELETE RESTRICT,
    FOREIGN KEY (installation_id)
        REFERENCES bridge_installations (installation_id)
        ON DELETE RESTRICT
);

-- migration-step
-- Diagnostic journaling only. This switch is not a network admission grant.
CREATE TABLE IF NOT EXISTS bridge_qa_installations (
    installation_id VARCHAR(128) PRIMARY KEY,
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    FOREIGN KEY (installation_id)
        REFERENCES bridge_installations (installation_id)
        ON DELETE RESTRICT
);

-- migration-step
CREATE TABLE IF NOT EXISTS bridge_qa_sessions (
    session_id VARCHAR(128) PRIMARY KEY,
    panel_instance VARCHAR(64) NOT NULL,
    user_id BIGINT NOT NULL,
    installation_id VARCHAR(128) NOT NULL,
    -- NULL means a legacy/unadmitted journal row and is never renewable.
    policy_revision BIGINT CHECK (policy_revision > 0),
    deadline TIMESTAMPTZ NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'active'
        CHECK (status IN ('active', 'closed', 'unconfirmed')),
    last_sequence BIGINT NOT NULL DEFAULT 0 CHECK (last_sequence >= 0),
    client_to_exit_bytes BIGINT NOT NULL DEFAULT 0 CHECK (client_to_exit_bytes >= 0),
    exit_to_client_bytes BIGINT NOT NULL DEFAULT 0 CHECK (exit_to_client_bytes >= 0),
    terminal_client_to_exit_bytes BIGINT CHECK (terminal_client_to_exit_bytes >= 0),
    terminal_exit_to_client_bytes BIGINT CHECK (terminal_exit_to_client_bytes >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    closed_at TIMESTAMPTZ,
    CHECK ((terminal_client_to_exit_bytes IS NULL) = (terminal_exit_to_client_bytes IS NULL)),
    CHECK (
        (status = 'active' AND closed_at IS NULL AND terminal_client_to_exit_bytes IS NULL)
        OR (status = 'closed' AND closed_at IS NOT NULL
            AND terminal_client_to_exit_bytes IS NOT NULL
            AND terminal_client_to_exit_bytes = client_to_exit_bytes
            AND terminal_exit_to_client_bytes = exit_to_client_bytes)
        OR (status = 'unconfirmed' AND closed_at IS NOT NULL)
    ),
    FOREIGN KEY (panel_instance, user_id)
        REFERENCES bridge_subjects (panel_instance, user_id)
        ON DELETE RESTRICT,
    FOREIGN KEY (installation_id)
        REFERENCES bridge_qa_installations (installation_id)
        ON DELETE RESTRICT
);

-- migration-step
-- Existing QA rows intentionally remain NULL: migration cannot invent the
-- policy revision under which their original admission occurred.
ALTER TABLE bridge_qa_sessions
    ADD COLUMN IF NOT EXISTS policy_revision BIGINT CHECK (policy_revision > 0);

-- migration-step
-- CREATE TABLE IF NOT EXISTS does not retrofit constraints on an existing
-- journal, so add this FK idempotently for both fresh and existing databases.
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conrelid = 'bridge_qa_sessions'::regclass
          AND conname = 'bridge_qa_sessions_installation_subject_fkey'
    ) THEN
        ALTER TABLE bridge_qa_sessions
            ADD CONSTRAINT bridge_qa_sessions_installation_subject_fkey
            FOREIGN KEY (installation_id, panel_instance, user_id)
            REFERENCES bridge_installations (installation_id, panel_instance, user_id)
            ON DELETE RESTRICT;
    END IF;
END
$$;

-- migration-step
CREATE INDEX IF NOT EXISTS bridge_qa_sessions_installation_idx
    ON bridge_qa_sessions (installation_id, status);

-- migration-step
CREATE UNIQUE INDEX IF NOT EXISTS bridge_qa_sessions_subject_identity_idx
    ON bridge_qa_sessions (session_id, panel_instance, user_id, installation_id);

-- migration-step
-- A consumed join nonce is stored only as a digest and cannot be reused after
-- reconnect or process restart. It is not a grant or subscription bearer.
CREATE TABLE IF NOT EXISTS bridge_qa_join_uses (
    nonce_sha256 BYTEA PRIMARY KEY CHECK (octet_length(nonce_sha256) = 32),
    session_id VARCHAR(128) NOT NULL UNIQUE,
    panel_instance VARCHAR(64) NOT NULL,
    user_id BIGINT NOT NULL,
    installation_id VARCHAR(128) NOT NULL,
    room_id VARCHAR(128) NOT NULL,
    country CHAR(2) NOT NULL CHECK (country ~ '^[A-Z]{2}$'),
    consumed_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    FOREIGN KEY (session_id, panel_instance, user_id, installation_id)
        REFERENCES bridge_qa_sessions (session_id, panel_instance, user_id, installation_id)
        ON DELETE RESTRICT
);
