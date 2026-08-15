package dev.plex.storage;

import dev.plex.api.storage.ModuleStorage;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Supplier;
import org.jdbi.v3.core.Jdbi;

public final class PremiumIdentityRepository
{
    private final Jdbi jdbi;
    private final Executor executor;
    private final String identitiesTable;
    private CompletableFuture<Void> operationTail = CompletableFuture.completedFuture(null);

    public PremiumIdentityRepository(ModuleStorage storage, Executor executor)
    {
        this.jdbi = storage.jdbi();
        this.executor = executor;
        this.identitiesTable = SqlIdentifier.quote(jdbi, storage.table("premium_identities"));
    }

    public CompletableFuture<Optional<UUID>> find(String username)
    {
        String normalizedUsername = normalize(username);
        return enqueue(() ->
        {
            try
            {
                return jdbi.withHandle(handle -> handle.createQuery(
                                "SELECT premium_uuid FROM " + identitiesTable + " WHERE username = :username")
                        .bind("username", normalizedUsername)
                        .mapTo(String.class)
                        .findFirst()
                        .map(UUID::fromString));
            }
            catch (RuntimeException exception)
            {
                throw new IllegalStateException("Failed to load premium identity", exception);
            }
        });
    }

    public CompletableFuture<Void> save(String username, UUID premiumUuid)
    {
        if (premiumUuid.version() != 4)
        {
            throw new IllegalArgumentException("Premium identity UUID must be version 4");
        }
        String normalizedUsername = normalize(username);
        return enqueue(() ->
        {
            try
            {
                jdbi.inTransaction(handle ->
                {
                    handle.createUpdate("DELETE FROM " + identitiesTable +
                                    " WHERE username = :username OR premium_uuid = :premiumUuid")
                            .bind("username", normalizedUsername)
                            .bind("premiumUuid", premiumUuid.toString())
                            .execute();
                    handle.createUpdate("INSERT INTO " + identitiesTable + " (username, premium_uuid, updated_at) " +
                                    "VALUES (:username, :premiumUuid, :updatedAt)")
                            .bind("username", normalizedUsername)
                            .bind("premiumUuid", premiumUuid.toString())
                            .bind("updatedAt", Instant.now().toEpochMilli())
                            .execute();
                    return null;
                });
                return null;
            }
            catch (RuntimeException exception)
            {
                throw new IllegalStateException("Failed to save premium identity", exception);
            }
        });
    }

    public CompletableFuture<Void> delete(UUID premiumUuid)
    {
        return enqueue(() ->
        {
            try
            {
                jdbi.useHandle(handle -> handle.createUpdate(
                                "DELETE FROM " + identitiesTable + " WHERE premium_uuid = :premiumUuid")
                        .bind("premiumUuid", premiumUuid.toString())
                        .execute());
                return null;
            }
            catch (RuntimeException exception)
            {
                throw new IllegalStateException("Failed to delete premium identity", exception);
            }
        });
    }

    private String normalize(String username)
    {
        return username.toLowerCase(Locale.ROOT);
    }

    private synchronized <T> CompletableFuture<T> enqueue(Supplier<T> operation)
    {
        CompletableFuture<T> future = operationTail.thenApplyAsync(ignored -> operation.get(), executor);
        operationTail = future.handle((result, throwable) -> null);
        return future;
    }
}
