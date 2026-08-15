# Module-2FA

Plex module providing TOTP authentication for players on offline-mode and proxy-backed servers.

## Enforcement

- `plex.2fa.required` requires authentication regardless of account classification.
- `plex.2fa.required.cracked` requires authentication when the connecting client claims the server-generated v3 offline UUID or supplies no claimed UUID. A normal premium client claiming a distinct v4 UUID is classified as `UNVERIFIED_PREMIUM_CLAIM` and is exempt from this permission.
- All Module-2FA permission nodes default to `false`, including for server operators.
- Client-claimed UUIDs are not proof of account ownership. The cracked-only distinction is a compatibility heuristic and can be spoofed by a modified client; use `plex.2fa.required` when enforcement must not be bypassable.
- The module installs a claimed v4 profile during async pre-login so normal premium clients retain their premium UUID on an offline-mode server. This explicitly trusts unauthenticated client data: a modified client can claim another player's v4 UUID and inherit UUID-bound data and permissions.

While authentication is pending, the player cannot move, teleport, chat, execute commands, interact, use inventories, modify blocks, exchange items, or deal or receive damage. Authentication times out after two minutes and five failed codes result in a kick.

Failed verification attempts persist across reconnects. Five failures within five minutes lock the account for five minutes, and a successful authentication clears the throttle.

## Enrollment

On first enforcement, an authenticated account receives a Paper dialog containing a Base32 secret for an authenticator app. Entering a valid six-digit code completes enrollment. Later logins prompt for a current code and reject reuse of an already-consumed time step.

Paper cannot prove ownership of a direct offline-mode identity. To prevent first-login account capture, an unauthenticated account waits for an administrator to verify the player out of band and run `/2fa authorize <player>` before its first enrollment dialog opens. Reset accounts require authorization again.

Players with `plex.2fa.required` or `plex.2fa.required.cracked` can also run `/2fa setup` to begin enrollment immediately. The dialog provides the issuer, account name, TOTP settings, and a clickable manual setup key. Existing secrets are never displayed again; an administrator must reset an existing enrollment before it can be replaced.

Permission changes should be followed by `/2fa setup` or a reconnect. Module re-enablement cannot reconstruct client login evidence for already-online players, so their account type remains unknown and cracked-only enforcement is deferred until their next connection.

## LuckPerms integration

When LuckPerms is installed, Plex exposes its API to Module-2FA as an optional classpath dependency. After a premium UUID completes 2FA, the module stores a durable lowercase username-to-premium-UUID mapping. Existing enrolled accounts are backfilled on their next v4 login.

If a later null/v3 client uses that username, Module-2FA applies the mapped premium UUID at `LOWEST` while retaining a `CRACKED` classification. LuckPerms therefore loads the premium user at its `LOW` pre-login handler, while 2FA remains mandatory under `plex.2fa.required.cracked`.

On every terminal authentication outcome, Module-2FA calls LuckPerms `deletePlayerData` for the offline UUID and then `savePlayerData` for the premium UUID and username. This cleans only LuckPerms' UUID/username cache; it does not delete permission nodes belonging to either UUID. Successful authentication remains locked until reconciliation completes. Without LuckPerms, these operations are no-ops.

## Storage and encryption

Accounts are stored through Plex module storage and support SQLite, MariaDB, and PostgreSQL. TOTP secrets are encrypted with AES-256-GCM.

The encryption key is loaded from the base64-encoded `PLEX_2FA_MASTER_KEY` environment variable when set. This environment/secret-manager option is recommended for production. Otherwise, the module atomically creates `two-factor.key` in its data directory and applies owner-only permissions on POSIX filesystems. Back up this key separately from the database; losing it makes existing enrollments unreadable.

Generate an environment key with:

```shell
openssl rand -base64 32
```

## Administration

`/2fa authorize <player|uuid>` requires `plex.2fa.admin` and approves the currently connected player for first-time unauthenticated enrollment after the administrator verifies their identity.

`/2fa reset <player|uuid>` requires `plex.2fa.admin`. It invalidates pending authentication work, deletes the stored enrollment, and disconnects an online target. An unauthenticated account must be authorized again before reenrollment.
