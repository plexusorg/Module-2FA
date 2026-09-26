package dev.plex.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.plex.TwoFactorModule;
import dev.plex.TwoFactorPermissions;
import dev.plex.api.player.PlexPlayerView;
import dev.plex.command.exception.PlayerNotFoundException;
import dev.plex.command.source.RequiredCommandSource;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
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
    protected void configureCommand(LiteralArgumentBuilder<CommandSourceStack> command)
    {
        command.executes(context -> executeCommand(context, (sender, player) -> executeTyped(sender, player, null, null)));
        command.then(word("action").suggests((context, builder) ->
        {
            CommandSender sender = context.getSource().getSender();
            List<String> actions = new ArrayList<>();
            if (sender instanceof Player player && canSetup(player)) actions.add("setup");
            if (sender.hasPermission(TwoFactorPermissions.ADMIN)) actions.addAll(List.of("reset", "authorize"));
            return suggestMatching(builder, actions);
        }).executes(context -> executeCommand(context, (sender, player) -> executeTyped(sender, player,
                        string(context, "action"), null)))
                .then(word("target").suggests((context, builder) ->
                {
                    String action = string(context, "action");
                    if ((action.equalsIgnoreCase("reset") || action.equalsIgnoreCase("authorize"))
                            && context.getSource().getSender().hasPermission(TwoFactorPermissions.ADMIN))
                    {
                        return suggestMatching(builder, onlinePlayerNames());
                    }
                    return builder.buildFuture();
                }).executes(context -> executeCommand(context, (sender, player) -> executeTyped(sender, player,
                                string(context, "action"), string(context, "target"))))
                        .then(greedyString("extra").executes(context -> executeCommand(context,
                                (sender, player) -> usage())))));
    }

    private Component executeTyped(CommandSender sender, @Nullable Player player, @Nullable String action, @Nullable String targetInput)
    {
        if (action == null)
        {
            if (player != null && module.authenticationManager().isLocked(player.getUniqueId()))
            {
                module.authenticationManager().reopen(player);
                return null;
            }
            return Component.text("Use /2fa setup to configure your authenticator.", NamedTextColor.GRAY);
        }

        if (targetInput == null && action.equalsIgnoreCase("setup"))
        {
            return setup(player);
        }

        if (targetInput != null && action.equalsIgnoreCase("authorize"))
        {
            return authorize(sender, targetInput);
        }

        if (targetInput == null || !action.equalsIgnoreCase("reset"))
        {
            return usage();
        }

        checkPermission(sender, TwoFactorPermissions.ADMIN);
        playerLookup(targetInput).whenComplete((target, lookupFailure) ->
        {
            if (lookupFailure != null)
            {
                if (sendPlayerLookupFailure(sender, lookupFailure))
                {
                    return;
                }
                sender.sendMessage(Component.text("Unable to look up " + targetInput + ".", NamedTextColor.RED));
                module.getLogger().error("Unable to look up two-factor authentication target {}", targetInput, lookupFailure);
                return;
            }
            if (target.isEmpty())
            {
                sender.sendMessage(Component.text("That player has never joined the server.", NamedTextColor.RED));
                return;
            }
            UUID targetUuid = target.orElseThrow().uuid();
            reset(sender, targetInput, targetUuid, Bukkit.getPlayer(targetUuid));
        });
        return null;
    }

    private Component setup(@Nullable Player player)
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

    private Component authorize(CommandSender sender, String targetInput)
    {
        checkPermission(sender, TwoFactorPermissions.ADMIN);
        Player target = onlinePlayer(targetInput);
        if (target == null)
        {
            return Component.text("That player is not online.", NamedTextColor.RED);
        }
        module.ownTask(target.getScheduler().run(module.plugin(), ignored ->
        {
            boolean authorized = module.authenticationManager().authorizeEnrollment(target);
            Component response = authorized
                    ? Component.text("Authorized two-factor enrollment for " + target.getName() + ".", NamedTextColor.GREEN)
                    : Component.text(target.getName() + " is not waiting for enrollment approval.", NamedTextColor.RED);
            sender.sendMessage(response);
        }, null));
        return null;
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
            return getNonNullPlayer(input);
        }
        catch (PlayerNotFoundException ignored)
        {
            return null;
        }
    }

    private CompletableFuture<Optional<PlexPlayerView>> playerLookup(String input)
    {
        try
        {
            return api().players().player(UUID.fromString(input));
        }
        catch (IllegalArgumentException ignored)
        {
            return api().players().resolveCommandPlayer(input);
        }
    }

    private void reset(CommandSender sender, String input, UUID targetUuid, @Nullable Player onlineTarget)
    {
        module.authenticationManager().reset(targetUuid, onlineTarget).whenComplete((unused, throwable) ->
        {
            if (throwable != null)
            {
                sender.sendMessage(Component.text("Unable to reset two-factor authentication for " + input + ".", NamedTextColor.RED));
                module.getLogger().error("Unable to reset two-factor authentication for {}", input, throwable);
                return;
            }
            sender.sendMessage(Component.text("Reset two-factor authentication for " + input + ".", NamedTextColor.GREEN));
        });
    }
}
