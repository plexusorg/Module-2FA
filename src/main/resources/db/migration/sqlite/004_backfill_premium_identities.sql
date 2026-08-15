INSERT OR REPLACE INTO {{table:premium_identities}} (username, premium_uuid, updated_at)
SELECT LOWER(players.last_known_name), accounts.player_uuid, CAST(strftime('%s', 'now') AS INTEGER) * 1000
FROM {{table:accounts}} accounts
INNER JOIN players ON players.uuid = accounts.player_uuid
WHERE players.last_known_name IS NOT NULL
  AND SUBSTR(accounts.player_uuid, 15, 1) = '4';
