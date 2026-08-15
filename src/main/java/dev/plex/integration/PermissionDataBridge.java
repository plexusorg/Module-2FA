package dev.plex.integration;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@FunctionalInterface
public interface PermissionDataBridge
{
    CompletableFuture<Void> reconcile(UUID crackedUuid, UUID premiumUuid, String username);

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
