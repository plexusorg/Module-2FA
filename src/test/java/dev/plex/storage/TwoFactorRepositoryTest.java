package dev.plex.storage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.plex.api.storage.ModuleMigrations;
import dev.plex.api.storage.ModuleStorage;
import dev.plex.crypto.SecretEncryption;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TwoFactorRepositoryTest
{
    @Test
    void persistsLockoutsAcrossRepositoryInstancesAndClearsThemOnSuccess(@TempDir Path dataFolder) throws Exception
    {
        Jdbi jdbi = Jdbi.create("jdbc:sqlite:" + dataFolder.resolve("two-factor-test.db"));
        applySqliteMigration(jdbi, "001_initial_schema");
        applySqliteMigration(jdbi, "002_authentication_throttle");

        ModuleStorage storage = storage(jdbi);
        TwoFactorRepository repository = new TwoFactorRepository(storage, Runnable::run, SecretEncryption.load(dataFolder));
        UUID playerUuid = UUID.randomUUID();
        byte[] secret = new byte[]{1, 2, 3, 4, 5};
        repository.create(playerUuid, secret, 10).join();
        assertTrue(repository.exists(playerUuid).join());
        assertFalse(repository.exists(UUID.randomUUID()).join());
        assertEquals(List.of(playerUuid), repository.findPlayerUuids().join());

        long currentTime = 1_000_000;
        AuthenticationThrottle throttle = null;
        for (int attempt = 0; attempt < 5; attempt++)
        {
            throttle = repository.recordFailedAttempt(playerUuid, currentTime + attempt, 5, 300_000, 300_000).join();
        }
        assertTrue(throttle.locked(currentTime + 5));

        TwoFactorRepository reconnectedRepository = new TwoFactorRepository(storage, Runnable::run, SecretEncryption.load(dataFolder));
        TwoFactorAccount lockedAccount = reconnectedRepository.find(playerUuid).join().orElseThrow();
        assertArrayEquals(secret, lockedAccount.secret());
        assertEquals(5, lockedAccount.throttle().failedAttempts());
        assertTrue(lockedAccount.throttle().locked(currentTime + 5));

        assertTrue(reconnectedRepository.consumeStep(playerUuid, 11).join());
        TwoFactorAccount authenticatedAccount = reconnectedRepository.find(playerUuid).join().orElseThrow();
        assertEquals(0, authenticatedAccount.throttle().failedAttempts());
        assertFalse(authenticatedAccount.throttle().locked(currentTime + 5));
    }

    @Test
    void premiumIdentityMappingIsCaseInsensitiveAndReplaceable(@TempDir Path dataFolder) throws Exception
    {
        Jdbi jdbi = Jdbi.create("jdbc:sqlite:" + dataFolder.resolve("identity-test.db"));
        applySqliteMigration(jdbi, "003_premium_identities");
        PremiumIdentityRepository repository = new PremiumIdentityRepository(storage(jdbi), Runnable::run);
        UUID firstUuid = UUID.fromString("f5cd54c4-3a24-4213-9a56-c06c49594dff");
        UUID secondUuid = UUID.randomUUID();

        repository.save("Taahh", firstUuid).join();
        assertEquals(firstUuid, repository.find("TAAHH").join().orElseThrow());

        repository.save("taahh", secondUuid).join();
        assertEquals(secondUuid, repository.find("Taahh").join().orElseThrow());

        repository.save("RenamedPlayer", secondUuid).join();
        assertTrue(repository.find("taahh").join().isEmpty());
        assertEquals(secondUuid, repository.find("renamedplayer").join().orElseThrow());

        repository.delete(secondUuid).join();
        assertTrue(repository.find("RenamedPlayer").join().isEmpty());
    }

    @Test
    void backfillsPremiumIdentityFromExistingTwoFactorAccount(@TempDir Path dataFolder) throws Exception
    {
        Jdbi jdbi = Jdbi.create("jdbc:sqlite:" + dataFolder.resolve("identity-backfill-test.db"));
        applySqliteMigration(jdbi, "001_initial_schema");
        applySqliteMigration(jdbi, "003_premium_identities");
        UUID premiumUuid = UUID.fromString("f5cd54c4-3a24-4213-9a56-c06c49594dff");
        UUID offlineUuid = UUID.fromString("c484fe6a-989a-3b1a-a97a-07a72a327095");
        jdbi.useHandle(handle ->
        {
            handle.execute("CREATE TABLE players (uuid VARCHAR(46) PRIMARY KEY, last_known_name VARCHAR(18))");
            handle.createUpdate("INSERT INTO players (uuid, last_known_name) VALUES (:uuid, :name)")
                    .bind("uuid", premiumUuid.toString())
                    .bind("name", "Taahh")
                    .execute();
            handle.createUpdate("INSERT INTO players (uuid, last_known_name) VALUES (:uuid, :name)")
                    .bind("uuid", offlineUuid.toString())
                    .bind("name", "taahh")
                    .execute();
        });

        TwoFactorRepository accountRepository = new TwoFactorRepository(
                storage(jdbi), Runnable::run, SecretEncryption.load(dataFolder));
        assertEquals(List.of(premiumUuid, offlineUuid), accountRepository.findPlayerUuidsByName("TAAHH").join());

        jdbi.useHandle(handle ->
        {
            handle.createUpdate("INSERT INTO \"2fa_accounts\" (player_uuid, encrypted_secret, secret_iv, created_at, last_used_step) " +
                            "VALUES (:uuid, 'secret', 'iv', 1, 1)")
                    .bind("uuid", premiumUuid.toString())
                    .execute();
        });

        applySqliteMigration(jdbi, "004_backfill_premium_identities");

        PremiumIdentityRepository repository = new PremiumIdentityRepository(storage(jdbi), Runnable::run);
        assertEquals(premiumUuid, repository.find("taahh").join().orElseThrow());
    }

    private void applySqliteMigration(Jdbi jdbi, String migration) throws IOException
    {
        String resource = "/db/migration/sqlite/" + migration + ".sql";
        try (var stream = TwoFactorRepositoryTest.class.getResourceAsStream(resource))
        {
            if (stream == null)
            {
                throw new IOException("Missing test migration resource: " + resource);
            }
            String script = new String(stream.readAllBytes())
                    .replace("{{table:accounts}}", "\"2fa_accounts\"")
                    .replace("{{table:premium_identities}}", "\"2fa_premium_identities\"");
            jdbi.useHandle(handle ->
            {
                for (String statement : script.split(";"))
                {
                    if (!statement.isBlank())
                    {
                        handle.execute(statement);
                    }
                }
            });
        }
    }

    private ModuleStorage storage(Jdbi jdbi)
    {
        return new ModuleStorage()
        {
            @Override
            public String prefix()
            {
                return "2fa";
            }

            @Override
            public String table(String localName)
            {
                return prefix() + "_" + localName;
            }

            @Override
            public ModuleMigrations migrations()
            {
                throw new UnsupportedOperationException();
            }

            @Override
            public Jdbi jdbi()
            {
                return jdbi;
            }
        };
    }
}
