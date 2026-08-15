ALTER TABLE {{table:accounts}} ADD COLUMN failed_attempts INTEGER NOT NULL DEFAULT 0;
ALTER TABLE {{table:accounts}} ADD COLUMN locked_until BIGINT NOT NULL DEFAULT 0;
ALTER TABLE {{table:accounts}} ADD COLUMN last_failed_at BIGINT NOT NULL DEFAULT 0;
