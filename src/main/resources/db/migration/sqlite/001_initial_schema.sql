CREATE TABLE IF NOT EXISTS {{table:accounts}} (
    player_uuid VARCHAR(36) NOT NULL PRIMARY KEY,
    encrypted_secret VARCHAR(512) NOT NULL,
    secret_iv VARCHAR(64) NOT NULL,
    created_at BIGINT NOT NULL,
    last_used_step BIGINT NOT NULL DEFAULT -1
);
