INSERT INTO {{table:premium_identities}} (username, premium_uuid, updated_at)
SELECT LOWER(players.last_known_name), accounts.player_uuid, CAST(EXTRACT(EPOCH FROM CURRENT_TIMESTAMP) * 1000 AS BIGINT)
FROM {{table:accounts}} accounts
INNER JOIN players ON players.uuid = accounts.player_uuid
WHERE players.last_known_name IS NOT NULL
  AND SUBSTRING(accounts.player_uuid FROM 15 FOR 1) = '4'
ON CONFLICT (username) DO UPDATE SET
    premium_uuid = EXCLUDED.premium_uuid,
    updated_at = EXCLUDED.updated_at;
