INSERT INTO {{table:premium_identities}} (username, premium_uuid, updated_at)
SELECT LOWER(players.last_known_name), accounts.player_uuid, UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000
FROM {{table:accounts}} accounts
INNER JOIN players ON players.uuid = accounts.player_uuid
WHERE players.last_known_name IS NOT NULL
  AND SUBSTRING(accounts.player_uuid, 15, 1) = '4'
ON DUPLICATE KEY UPDATE
    premium_uuid = VALUES(premium_uuid),
    updated_at = VALUES(updated_at);
