CREATE TABLE IF NOT EXISTS {{table:premium_identities}} (
    username VARCHAR(16) NOT NULL PRIMARY KEY,
    premium_uuid VARCHAR(36) NOT NULL,
    updated_at BIGINT NOT NULL
);
