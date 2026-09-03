package dev.plex.integration;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@FunctionalInterface
public interface PermissionDataBridge
{
    CompletableFuture<Void> reconcile(UUID crackedUuid, UUID premiumUuid, String username);

    default void reconcileForShutdown(UUID crackedUuid, UUID premiumUuid, String username, long timeoutNanos) throws Exception
    {
        reconcile(crackedUuid, premiumUuid, username).get(timeoutNanos, TimeUnit.NANOSECONDS);
    }

    default CompletableFuture<Optional<UUID>> lookupUniqueId(String username)
    {
        return CompletableFuture.completedFuture(Optional.empty());
    }

    default CompletableFuture<Optional<String>> lookupUsername(UUID playerUuid)
    {
        return CompletableFuture.completedFuture(Optional.empty());
    }

    default CompletableFuture<Boolean> requiresTwoFactor(UUID playerUuid)
    {
        return CompletableFuture.completedFuture(false);
    }

    static PermissionDataBridge unavailable()
    {
        return (crackedUuid, premiumUuid, username) -> CompletableFuture.completedFuture(null);
    }
}
