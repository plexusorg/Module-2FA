package dev.plex.integration;

import dev.plex.TwoFactorPermissions;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.model.user.UserManager;
import org.bukkit.Bukkit;
import org.bukkit.plugin.RegisteredServiceProvider;

public final class LuckPermsPermissionDataBridge implements PermissionDataBridge
{
    private final UserManager userManager;

    public LuckPermsPermissionDataBridge(LuckPerms luckPerms)
    {
        this.userManager = luckPerms.getUserManager();
    }

    public static PermissionDataBridge fromServices()
    {
        RegisteredServiceProvider<LuckPerms> provider = Bukkit.getServicesManager().getRegistration(LuckPerms.class);
        if (provider == null)
        {
            throw new IllegalStateException("LuckPerms service is not registered");
        }
        return new LuckPermsPermissionDataBridge(provider.getProvider());
    }

    @Override
    public CompletableFuture<Void> reconcile(UUID crackedUuid, UUID premiumUuid, String username)
    {
        if (crackedUuid.equals(premiumUuid))
        {
            return CompletableFuture.completedFuture(null);
        }
        return userManager.deletePlayerData(crackedUuid)
                .thenCompose(ignored -> userManager.savePlayerData(premiumUuid, username))
                .thenApply(ignored -> null);
    }

    @Override
    public void reconcileForShutdown(UUID crackedUuid, UUID premiumUuid, String username, long timeoutNanos) throws Exception
    {
        if (crackedUuid.equals(premiumUuid))
        {
            return;
        }
        long deadline = System.nanoTime() + timeoutNanos;
        userManager.deletePlayerData(crackedUuid).get(timeoutNanos, TimeUnit.NANOSECONDS);
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0)
        {
            throw new java.util.concurrent.TimeoutException("LuckPerms reconciliation timed out");
        }
        userManager.savePlayerData(premiumUuid, username).get(remaining, TimeUnit.NANOSECONDS);
    }

    @Override
    public CompletableFuture<Optional<UUID>> lookupUniqueId(String username)
    {
        return userManager.lookupUniqueId(username).thenApply(Optional::ofNullable);
    }

    @Override
    public CompletableFuture<Optional<String>> lookupUsername(UUID playerUuid)
    {
        return userManager.lookupUsername(playerUuid).thenApply(Optional::ofNullable);
    }

    @Override
    public CompletableFuture<Boolean> requiresTwoFactor(UUID playerUuid)
    {
        return userManager.loadUser(playerUuid).thenApply(user ->
        {
            var permissionData = user.getCachedData().getPermissionData();
            return permissionData.checkPermission(TwoFactorPermissions.REQUIRED).asBoolean()
                    || permissionData.checkPermission(TwoFactorPermissions.REQUIRED_CRACKED).asBoolean();
        });
    }
}
