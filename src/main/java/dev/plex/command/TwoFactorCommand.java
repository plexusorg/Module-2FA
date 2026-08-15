package dev.plex.command;

import dev.plex.TwoFactorModule;
import dev.plex.TwoFactorPermissions;
import dev.plex.command.source.RequiredCommandSource;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class TwoFactorCommand extends SimplePlexCommand
{
    private final TwoFactorModule module;

    public TwoFactorCommand(TwoFactorModule module)
    {
        super(command("2fa")
                .description("Manages two-factor authentication")
                .usage("/2fa setup | /2fa authorize <player|uuid> | /2fa reset <player|uuid>")
                .aliases("twofactor")
                .source(RequiredCommandSource.ANY)
                .build());
        this.module = module;
    }

    @Override
    protected Component execute(@NotNull CommandSender sender, @Nullable Player player, @NotNull String[] args)
    {
        if (args.length == 0)
        {
            if (player != null && module.authenticationManager().isLocked(player.getUniqueId()))
            {
                module.authenticationManager().reopen(player);
                return null;
            }
            return Component.text("Use /2fa setup to configure your authenticator.", NamedTextColor.GRAY);
        }

        if (args.length == 1 && args[0].equalsIgnoreCase("setup"))
        {
            if (player == null)
            {
                return Component.text("Only players can set up two-factor authentication.", NamedTextColor.RED);
            }
            if (!canSetup(player))
            {
                return Component.text("You must have plex.2fa.required or plex.2fa.required.cracked to set up two-factor authentication.", NamedTextColor.RED);
            }
            module.authenticationManager().setup(player);
            return null;
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("authorize"))
        {
            checkPermission(sender, TwoFactorPermissions.ADMIN);
            Player target = onlinePlayer(args[1]);
            if (target == null)
            {
                return Component.text("That player is not online.", NamedTextColor.RED);
            }
            module.api().scheduler().runEntity(target, () ->
            {
                boolean authorized = module.authenticationManager().authorizeEnrollment(target);
                Component response = authorized
                        ? Component.text("Authorized two-factor enrollment for " + target.getName() + ".", NamedTextColor.GREEN)
                        : Component.text(target.getName() + " is not waiting for enrollment approval.", NamedTextColor.RED);
                respond(sender, response);
            });
            return null;
        }

        if (args.length != 2 || !args[0].equalsIgnoreCase("reset"))
        {
            return usage();
        }

        checkPermission(sender, TwoFactorPermissions.ADMIN);
        Player onlineTarget = onlinePlayer(args[1]);
        OfflinePlayer target = onlineTarget == null ? offlinePlayer(args[1]) : onlineTarget;
        UUID targetUuid = target.getUniqueId();
        module.authenticationManager().reset(targetUuid, onlineTarget).whenComplete((unused, throwable) ->
        {
            if (throwable != null)
            {
                respond(sender, Component.text("Unable to reset two-factor authentication for " + args[1] + ".", NamedTextColor.RED));
                module.getLogger().error("Unable to reset two-factor authentication for {}", args[1], throwable);
                return;
            }

            respond(sender, Component.text("Reset two-factor authentication for " + args[1] + ".", NamedTextColor.GREEN));
        });
        return null;
    }

    @Override
    protected @NotNull List<String> suggestions(@NotNull CommandSender sender, @NotNull String alias, @NotNull String[] args)
    {
        if (args.length <= 1)
        {
            List<String> suggestions = new ArrayList<>();
            if (sender instanceof Player player && canSetup(player))
            {
                suggestions.add("setup");
            }
            if (sender.hasPermission(TwoFactorPermissions.ADMIN))
            {
                suggestions.add("reset");
                suggestions.add("authorize");
            }
            return suggestions;
        }
        if (args.length == 2
                && (args[0].equalsIgnoreCase("reset") || args[0].equalsIgnoreCase("authorize"))
                && sender.hasPermission(TwoFactorPermissions.ADMIN))
        {
            return new ArrayList<>(onlinePlayerNames());
        }
        return List.of();
    }

    private boolean canSetup(Player player)
    {
        return player.hasPermission(TwoFactorPermissions.REQUIRED)
                || player.hasPermission(TwoFactorPermissions.REQUIRED_CRACKED);
    }

    private Player onlinePlayer(String input)
    {
        try
        {
            return Bukkit.getPlayer(UUID.fromString(input));
        }
        catch (IllegalArgumentException ignored)
        {
            return Bukkit.getPlayerExact(input);
        }
    }

    private OfflinePlayer offlinePlayer(String input)
    {
        try
        {
            return Bukkit.getOfflinePlayer(UUID.fromString(input));
        }
        catch (IllegalArgumentException ignored)
        {
            return Bukkit.getOfflinePlayer(input);
        }
    }

    private void respond(CommandSender sender, Component message)
    {
        if (sender instanceof Player player)
        {
            module.api().scheduler().runEntity(player, () -> player.sendMessage(message));
            return;
        }
        module.api().scheduler().runGlobal(() -> sender.sendMessage(message));
    }
}
