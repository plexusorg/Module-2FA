package dev.plex;

import dev.plex.api.storage.ModuleStorage;
import dev.plex.auth.AccountClassifier;
import dev.plex.auth.AuthenticationManager;
import dev.plex.command.TwoFactorCommand;
import dev.plex.crypto.SecretEncryption;
import dev.plex.crypto.TotpService;
import dev.plex.dialog.TwoFactorDialogService;
import dev.plex.integration.LuckPermsPermissionDataBridge;
import dev.plex.integration.PermissionDataBridge;
import dev.plex.listener.AuthenticationListener;
import dev.plex.module.PlexModule;
import dev.plex.storage.TwoFactorRepository;
import dev.plex.storage.PremiumIdentityRepository;
import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.bukkit.Bukkit;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.PluginManager;

public final class TwoFactorModule extends PlexModule
{
    private AuthenticationManager authenticationManager;
    private ExecutorService executor;
    private final List<Permission> registeredPermissions = new ArrayList<>();

    @Override
    public void load()
    {
        registerCommand(new TwoFactorCommand(this));
    }

    @Override
    public void enable()
    {
        executor = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("Plex-2FA-", 0).factory());
        ModuleStorage storage = api().storage().forModule(this);
        try
        {
            storage.migrations().run();
            SecretEncryption encryption = SecretEncryption.load(getDataFolder().toPath());
            TwoFactorRepository repository = new TwoFactorRepository(storage, executor, encryption);
            PremiumIdentityRepository identityRepository = new PremiumIdentityRepository(storage, executor);
            PermissionDataBridge permissionDataBridge = permissionDataBridge();
            authenticationManager = new AuthenticationManager(
                    this,
                    new AccountClassifier(api().players(), identityRepository, repository, permissionDataBridge),
                    repository,
                    identityRepository,
                    permissionDataBridge,
                    new TotpService(),
                    new TwoFactorDialogService());
        }
        catch (SQLException | IOException exception)
        {
            throw new IllegalStateException("Unable to initialize two-factor authentication", exception);
        }

        registerPermissions();
        registerListener(new AuthenticationListener(authenticationManager));
        Bukkit.getOnlinePlayers().forEach(player -> ownTask(player.getScheduler().run(plugin(),
                ignored -> authenticationManager.handleJoin(player), null)));
        getLogger().warn("Offline-mode v4 UUID claims are trusted and installed as player UUIDs; modified clients can spoof these identities");
        getLogger().info("Two-factor authentication enabled");
    }

    @Override
    public void disable()
    {
        if (authenticationManager != null)
        {
            completeShutdownBeforeClose(authenticationManager.shutdown());
        }
        if (executor != null)
        {
            executor.shutdown();
            executor = null;
        }
        PluginManager pluginManager = Bukkit.getPluginManager();
        registeredPermissions.forEach(pluginManager::removePermission);
        registeredPermissions.clear();
    }

    public ExecutorService executor()
    {
        if (executor == null) throw new IllegalStateException("Two-factor authentication is not enabled");
        return executor;
    }

    public AuthenticationManager authenticationManager()
    {
        if (authenticationManager == null)
        {
            throw new IllegalStateException("Two-factor authentication is not enabled");
        }
        return authenticationManager;
    }

    private void registerPermissions()
    {
        PluginManager pluginManager = Bukkit.getPluginManager();
        for (String node : TwoFactorPermissions.ALL)
        {
            if (pluginManager.getPermission(node) != null)
            {
                continue;
            }
            Permission permission = new Permission(node, PermissionDefault.FALSE);
            pluginManager.addPermission(permission);
            registeredPermissions.add(permission);
        }
    }

    private PermissionDataBridge permissionDataBridge()
    {
        if (!Bukkit.getPluginManager().isPluginEnabled("LuckPerms"))
        {
            return PermissionDataBridge.unavailable();
        }
        try
        {
            PermissionDataBridge bridge = LuckPermsPermissionDataBridge.fromServices();
            getLogger().info("LuckPerms UUID cache reconciliation enabled");
            return bridge;
        }
        catch (LinkageError | RuntimeException exception)
        {
            getLogger().warn("LuckPerms is present but its API is unavailable to Module-2FA", exception);
            return PermissionDataBridge.unavailable();
        }
    }
}
