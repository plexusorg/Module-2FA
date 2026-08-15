package dev.plex.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.plex.TwoFactorPermissions;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.cacheddata.CachedDataManager;
import net.luckperms.api.cacheddata.CachedPermissionData;
import net.luckperms.api.model.user.User;
import net.luckperms.api.model.user.UserManager;
import net.luckperms.api.util.Tristate;
import org.junit.jupiter.api.Test;

class LuckPermsPermissionDataBridgeTest
{
    @Test
    void looksUpStoredUuidByUsername()
    {
        UUID premiumUuid = UUID.fromString("f5cd54c4-3a24-4213-9a56-c06c49594dff");
        UserManager userManager = proxy(UserManager.class, (method, arguments) ->
        {
            if (method.equals("lookupUniqueId"))
            {
                return CompletableFuture.completedFuture(premiumUuid);
            }
            throw new UnsupportedOperationException(method);
        });
        LuckPerms luckPerms = proxy(LuckPerms.class, (method, arguments) ->
        {
            if (method.equals("getUserManager"))
            {
                return userManager;
            }
            throw new UnsupportedOperationException(method);
        });

        assertEquals(premiumUuid, new LuckPermsPermissionDataBridge(luckPerms)
                .lookupUniqueId("Taahh").join().orElseThrow());
    }

    @Test
    void looksUpStoredUsernameByUuid()
    {
        UUID premiumUuid = UUID.fromString("f5cd54c4-3a24-4213-9a56-c06c49594dff");
        UserManager userManager = proxy(UserManager.class, (method, arguments) ->
        {
            if (method.equals("lookupUsername"))
            {
                return CompletableFuture.completedFuture("Taahh");
            }
            throw new UnsupportedOperationException(method);
        });
        LuckPerms luckPerms = proxy(LuckPerms.class, (method, arguments) ->
        {
            if (method.equals("getUserManager"))
            {
                return userManager;
            }
            throw new UnsupportedOperationException(method);
        });

        assertEquals("Taahh", new LuckPermsPermissionDataBridge(luckPerms)
                .lookupUsername(premiumUuid).join().orElseThrow());
    }

    @Test
    void detectsRequiredPermissionOnStoredPremiumUser()
    {
        UUID premiumUuid = UUID.fromString("f5cd54c4-3a24-4213-9a56-c06c49594dff");
        CachedPermissionData permissionData = proxy(CachedPermissionData.class, (method, arguments) ->
        {
            if (method.equals("checkPermission"))
            {
                return TwoFactorPermissions.REQUIRED_CRACKED.equals(arguments[0])
                        ? Tristate.TRUE
                        : Tristate.UNDEFINED;
            }
            throw new UnsupportedOperationException(method);
        });
        CachedDataManager cachedData = proxy(CachedDataManager.class, (method, arguments) ->
        {
            if (method.equals("getPermissionData"))
            {
                return permissionData;
            }
            throw new UnsupportedOperationException(method);
        });
        User user = proxy(User.class, (method, arguments) ->
        {
            if (method.equals("getCachedData"))
            {
                return cachedData;
            }
            throw new UnsupportedOperationException(method);
        });
        UserManager userManager = proxy(UserManager.class, (method, arguments) ->
        {
            if (method.equals("loadUser"))
            {
                return CompletableFuture.completedFuture(user);
            }
            throw new UnsupportedOperationException(method);
        });
        LuckPerms luckPerms = proxy(LuckPerms.class, (method, arguments) ->
        {
            if (method.equals("getUserManager"))
            {
                return userManager;
            }
            throw new UnsupportedOperationException(method);
        });

        assertTrue(new LuckPermsPermissionDataBridge(luckPerms).requiresTwoFactor(premiumUuid).join());
    }

    @Test
    void deletesCrackedMappingBeforeRestoringPremiumMapping()
    {
        List<String> operations = new ArrayList<>();
        UserManager userManager = proxy(UserManager.class, (method, arguments) ->
        {
            if (method.equals("deletePlayerData"))
            {
                operations.add("delete:" + arguments[0]);
                return CompletableFuture.completedFuture(null);
            }
            if (method.equals("savePlayerData"))
            {
                operations.add("save:" + arguments[0] + ":" + arguments[1]);
                return CompletableFuture.completedFuture(null);
            }
            throw new UnsupportedOperationException(method);
        });
        LuckPerms luckPerms = proxy(LuckPerms.class, (method, arguments) ->
        {
            if (method.equals("getUserManager"))
            {
                return userManager;
            }
            throw new UnsupportedOperationException(method);
        });
        UUID crackedUuid = UUID.fromString("c484fe6a-989a-3b1a-a97a-07a72a327095");
        UUID premiumUuid = UUID.fromString("f5cd54c4-3a24-4213-9a56-c06c49594dff");

        new LuckPermsPermissionDataBridge(luckPerms).reconcile(crackedUuid, premiumUuid, "Taahh").join();

        assertEquals(List.of("delete:" + crackedUuid, "save:" + premiumUuid + ":Taahh"), operations);
    }

    @SuppressWarnings("unchecked")
    private <T> T proxy(Class<T> type, Invocation invocation)
    {
        return (T) Proxy.newProxyInstance(
                type.getClassLoader(),
                new Class<?>[]{type},
                (proxy, method, arguments) -> invocation.invoke(method.getName(), arguments));
    }

    @FunctionalInterface
    private interface Invocation
    {
        Object invoke(String method, Object[] arguments);
    }
}
