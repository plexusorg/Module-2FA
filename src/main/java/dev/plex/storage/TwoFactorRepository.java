package dev.plex.storage;

import dev.plex.api.storage.ModuleStorage;
import dev.plex.crypto.EncryptedSecret;
import dev.plex.crypto.SecretEncryption;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.jdbi.v3.core.Jdbi;

public final class TwoFactorRepository
{
    private final Jdbi jdbi;
    private final Executor executor;
    private final SecretEncryption encryption;
    private final String accountsTable;
    private final Map<UUID, CompletableFuture<Void>> operationTails = new ConcurrentHashMap<>();

    public TwoFactorRepository(ModuleStorage storage, Executor executor, SecretEncryption encryption)
    {
        this.jdbi = storage.jdbi();
        this.executor = executor;
        this.encryption = encryption;
        this.accountsTable = SqlIdentifier.quote(jdbi, storage.table("accounts"));
    }

    public CompletableFuture<Optional<TwoFactorAccount>> find(UUID playerUuid)
    {
        return enqueue(playerUuid, () ->
        {
            try
            {
                return jdbi.withHandle(handle -> handle.createQuery("SELECT encrypted_secret, secret_iv, last_used_step, failed_attempts, locked_until, last_failed_at FROM " + accountsTable + " WHERE player_uuid = :uuid")
                        .bind("uuid", playerUuid.toString())
                        .map((resultSet, context) -> new TwoFactorAccount(
                                playerUuid,
                                encryption.decrypt(playerUuid, resultSet.getString("encrypted_secret"), resultSet.getString("secret_iv")),
                                resultSet.getLong("last_used_step"),
                                new AuthenticationThrottle(
                                        resultSet.getInt("failed_attempts"),
                                        resultSet.getLong("locked_until"),
                                        resultSet.getLong("last_failed_at"))))
                        .findFirst());
            }
            catch (RuntimeException exception)
            {
                throw new IllegalStateException("Failed to load two-factor account", exception);
            }
        });
    }

    public CompletableFuture<Boolean> exists(UUID playerUuid)
    {
        return enqueue(playerUuid, () ->
        {
            try
            {
                return jdbi.withHandle(handle -> handle.createQuery(
                                "SELECT COUNT(*) FROM " + accountsTable + " WHERE player_uuid = :uuid")
                        .bind("uuid", playerUuid.toString())
                        .mapTo(Integer.class)
                        .one()) > 0;
            }
            catch (RuntimeException exception)
            {
                throw new IllegalStateException("Failed to check two-factor account", exception);
            }
        });
    }

    public CompletableFuture<List<UUID>> findPlayerUuids()
    {
        return CompletableFuture.supplyAsync(() ->
        {
            try
            {
                return jdbi.withHandle(handle -> handle.createQuery(
                                "SELECT player_uuid FROM " + accountsTable)
                        .mapTo(String.class)
                        .list()
                        .stream()
                        .map(UUID::fromString)
                        .toList());
            }
            catch (RuntimeException exception)
            {
                throw new IllegalStateException("Failed to load two-factor account UUIDs", exception);
            }
        }, executor);
    }

    public CompletableFuture<List<UUID>> findPlayerUuidsByName(String username)
    {
        return CompletableFuture.supplyAsync(() ->
        {
            try
            {
                return jdbi.withHandle(handle -> handle.createQuery(
                                "SELECT uuid FROM players WHERE LOWER(last_known_name) = LOWER(:username)")
                        .bind("username", username)
                        .mapTo(String.class)
                        .list()
                        .stream()
                        .map(UUID::fromString)
                        .filter(playerUuid -> playerUuid.version() == 3 || playerUuid.version() == 4)
                        .sorted(Comparator.comparingInt(playerUuid -> playerUuid.version() == 4 ? 0 : 1))
                        .toList());
            }
            catch (RuntimeException exception)
            {
                throw new IllegalStateException("Failed to resolve two-factor account by player name", exception);
            }
        }, executor);
    }

    public CompletableFuture<Void> create(UUID playerUuid, byte[] secret, long initialStep)
    {
        EncryptedSecret encryptedSecret = encryption.encrypt(playerUuid, secret);
        return enqueue(playerUuid, () ->
        {
            try
            {
                jdbi.useHandle(handle -> handle.createUpdate("INSERT INTO " + accountsTable + " (player_uuid, encrypted_secret, secret_iv, created_at, last_used_step) " +
                                "VALUES (:uuid, :secret, :iv, :createdAt, :lastUsedStep)")
                        .bind("uuid", playerUuid.toString())
                        .bind("secret", encryptedSecret.ciphertext())
                        .bind("iv", encryptedSecret.initializationVector())
                        .bind("createdAt", Instant.now().toEpochMilli())
                        .bind("lastUsedStep", initialStep)
                        .execute());
                return null;
            }
            catch (RuntimeException exception)
            {
                throw new IllegalStateException("Failed to create two-factor account", exception);
            }
        });
    }

    public CompletableFuture<Boolean> consumeStep(UUID playerUuid, long step)
    {
        return enqueue(playerUuid, () ->
        {
            try
            {
                return jdbi.withHandle(handle -> handle.createUpdate("UPDATE " + accountsTable + " SET last_used_step = :step, failed_attempts = 0, locked_until = 0, last_failed_at = 0 " +
                                "WHERE player_uuid = :uuid AND last_used_step < :step")
                        .bind("step", step)
                        .bind("uuid", playerUuid.toString())
                        .execute()) == 1;
            }
            catch (RuntimeException exception)
            {
                throw new IllegalStateException("Failed to consume two-factor code", exception);
            }
        });
    }

    public CompletableFuture<AuthenticationThrottle> recordFailedAttempt(
            UUID playerUuid,
            long currentTime,
            int maximumAttempts,
            long failureWindowMillis,
            long lockoutMillis)
    {
        return enqueue(playerUuid, () ->
        {
            try
            {
                return jdbi.inTransaction(handle ->
                {
                    String attemptsExpression = "CASE WHEN last_failed_at = 0 OR last_failed_at < :windowStart THEN 1 ELSE failed_attempts + 1 END";
                    handle.createUpdate("UPDATE " + accountsTable + " SET failed_attempts = " + attemptsExpression +
                                    ", locked_until = 0, last_failed_at = :currentTime WHERE player_uuid = :uuid AND locked_until <= :currentTime")
                            .bind("windowStart", currentTime - failureWindowMillis)
                            .bind("currentTime", currentTime)
                            .bind("uuid", playerUuid.toString())
                            .execute();
                    handle.createUpdate("UPDATE " + accountsTable + " SET locked_until = :newLockedUntil " +
                                    "WHERE player_uuid = :uuid AND failed_attempts >= :maximumAttempts AND locked_until <= :currentTime")
                            .bind("newLockedUntil", currentTime + lockoutMillis)
                            .bind("maximumAttempts", maximumAttempts)
                            .bind("currentTime", currentTime)
                            .bind("uuid", playerUuid.toString())
                            .execute();
                    return handle.createQuery(
                                    "SELECT failed_attempts, locked_until, last_failed_at FROM " + accountsTable + " WHERE player_uuid = :uuid")
                            .bind("uuid", playerUuid.toString())
                            .map((resultSet, context) -> new AuthenticationThrottle(
                                    resultSet.getInt("failed_attempts"),
                                    resultSet.getLong("locked_until"),
                                    resultSet.getLong("last_failed_at")))
                            .findFirst()
                            .orElseThrow(() -> new IllegalStateException("Two-factor account no longer exists"));
                });
            }
            catch (RuntimeException exception)
            {
                throw new IllegalStateException("Failed to record two-factor authentication attempt", exception);
            }
        });
    }

    public CompletableFuture<Void> delete(UUID playerUuid)
    {
        return enqueue(playerUuid, () ->
        {
            try
            {
                jdbi.useHandle(handle -> handle.createUpdate("DELETE FROM " + accountsTable + " WHERE player_uuid = :uuid")
                        .bind("uuid", playerUuid.toString())
                        .execute());
                return null;
            }
            catch (RuntimeException exception)
            {
                throw new IllegalStateException("Failed to delete two-factor account", exception);
            }
        });
    }

    private <T> CompletableFuture<T> enqueue(UUID playerUuid, Supplier<T> operation)
    {
        AtomicReference<CompletableFuture<T>> submitted = new AtomicReference<>();
        AtomicReference<CompletableFuture<Void>> submittedTail = new AtomicReference<>();
        operationTails.compute(playerUuid, (uuid, tail) ->
        {
            CompletableFuture<Void> prerequisite = tail == null ? CompletableFuture.completedFuture(null) : tail;
            CompletableFuture<T> future = prerequisite.thenApplyAsync(ignored -> operation.get(), executor);
            submitted.set(future);
            CompletableFuture<Void> nextTail = future.handle((result, throwable) -> null);
            submittedTail.set(nextTail);
            return nextTail;
        });
        CompletableFuture<Void> tail = submittedTail.get();
        tail.whenComplete((ignored, throwable) -> operationTails.remove(playerUuid, tail));
        return submitted.get();
    }
}
