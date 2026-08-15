package dev.plex.dialog;

import dev.plex.crypto.Base32;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.entity.Player;

public final class TwoFactorDialogService
{
    private static final String CODE_INPUT = "code";
    private static final ClickCallback.Options CALLBACK_OPTIONS = ClickCallback.Options.builder()
            .uses(1)
            .lifetime(Duration.ofMinutes(2))
            .build();

    public void openEnrollment(
            Player player,
            byte[] secret,
            String error,
            BiConsumer<Player, String> callback,
            Consumer<Player> chatCallback,
            Consumer<Player> disconnectCallback)
    {
        String encodedSecret = Base32.encode(secret);
        List<DialogBody> body = new ArrayList<>();
        body.add(DialogBody.plainMessage(text("Two-factor authentication is required for this account.", NamedTextColor.YELLOW)));
        body.add(DialogBody.plainMessage(text("In your authenticator app, choose to enter a setup key manually.", NamedTextColor.GRAY)));
        body.add(DialogBody.plainMessage(text("Issuer: Plex    Account: " + player.getName(), NamedTextColor.GRAY)));
        body.add(DialogBody.plainMessage(text("Type: Time based (TOTP)    Digits: 6    Period: 30 seconds", NamedTextColor.GRAY)));
        body.add(DialogBody.plainMessage(text("Setup key:", NamedTextColor.GRAY)));
        body.add(DialogBody.plainMessage(Component.text(group(encodedSecret), NamedTextColor.AQUA)
                .decoration(TextDecoration.ITALIC, false)
                .clickEvent(ClickEvent.copyToClipboard(encodedSecret))));
        body.add(DialogBody.plainMessage(text("Click the key to copy it, then enter the generated six-digit code.", NamedTextColor.GRAY)));
        addError(body, error);
        player.showDialog(dialog("Set Up Two-Factor Authentication", body, "Enable 2FA", callback, chatCallback, disconnectCallback));
    }

    public void openVerification(
            Player player,
            String error,
            BiConsumer<Player, String> callback,
            Consumer<Player> chatCallback,
            Consumer<Player> disconnectCallback)
    {
        List<DialogBody> body = new ArrayList<>();
        body.add(DialogBody.plainMessage(text("Enter the current six-digit code from your authenticator app.", NamedTextColor.GRAY)));
        body.add(DialogBody.plainMessage(text("You cannot move, chat, interact, or execute commands until authentication succeeds.", NamedTextColor.YELLOW)));
        addError(body, error);
        player.showDialog(dialog("Two-Factor Authentication", body, "Authenticate", callback, chatCallback, disconnectCallback));
    }

    public void sendEnrollmentChatPrompt(Player player, byte[] secret, String error)
    {
        String encodedSecret = Base32.encode(secret);
        player.sendMessage(text("Two-factor authentication chat mode is active.", NamedTextColor.YELLOW));
        player.sendMessage(text("Issuer: Plex    Account: " + player.getName(), NamedTextColor.GRAY));
        player.sendMessage(Component.text("Setup key: ", NamedTextColor.GRAY)
                .append(Component.text(group(encodedSecret), NamedTextColor.AQUA)
                        .clickEvent(ClickEvent.copyToClipboard(encodedSecret))));
        sendChatError(player, error);
        player.sendMessage(text("Send the current six-digit code in chat. It will not be broadcast.", NamedTextColor.GREEN));
    }

    public void sendVerificationChatPrompt(Player player, String error)
    {
        player.sendMessage(text("Two-factor authentication chat mode is active.", NamedTextColor.YELLOW));
        sendChatError(player, error);
        player.sendMessage(text("Send the current six-digit code in chat. It will not be broadcast.", NamedTextColor.GREEN));
    }

    public void openEnrollmentApproval(Player player, Consumer<Player> disconnectCallback)
    {
        List<DialogBody> body = new ArrayList<>();
        body.add(DialogBody.plainMessage(text("An administrator must approve first-time enrollment for this unauthenticated account.", NamedTextColor.YELLOW)));
        body.add(DialogBody.plainMessage(text("Ask an administrator to run /2fa authorize " + player.getName() + " after verifying your identity.", NamedTextColor.GRAY)));

        ActionButton disconnect = ActionButton.builder(text("Disconnect", NamedTextColor.RED))
                .tooltip(text("Leave the server", NamedTextColor.GRAY))
                .width(200)
                .action(DialogAction.customClick((response, audience) -> acceptPlayer(audience, disconnectCallback), CALLBACK_OPTIONS))
                .build();
        player.showDialog(Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(text("2FA Enrollment Approval Required", NamedTextColor.DARK_AQUA))
                        .canCloseWithEscape(false)
                        .pause(false)
                        .afterAction(DialogBase.DialogAfterAction.NONE)
                        .body(body)
                        .build())
                .type(DialogType.notice(disconnect))));
    }

    private Dialog dialog(
            String title,
            List<DialogBody> body,
            String submitLabel,
            BiConsumer<Player, String> callback,
            Consumer<Player> chatCallback,
            Consumer<Player> disconnectCallback)
    {
        ActionButton submit = ActionButton.builder(text(submitLabel, NamedTextColor.GREEN))
                .tooltip(text("Submit the current authentication code", NamedTextColor.GRAY))
                .width(200)
                .action(DialogAction.customClick((response, audience) -> submit(audience, response.getText(CODE_INPUT), callback), CALLBACK_OPTIONS))
                .build();
        ActionButton switchToChat = ActionButton.builder(text("Switch to Chat", NamedTextColor.AQUA))
                .tooltip(text("Enter your authentication code through chat", NamedTextColor.GRAY))
                .width(200)
                .action(DialogAction.customClick((response, audience) -> acceptPlayer(audience, chatCallback), CALLBACK_OPTIONS))
                .build();
        ActionButton disconnect = ActionButton.builder(text("Disconnect", NamedTextColor.RED))
                .tooltip(text("Leave the server", NamedTextColor.GRAY))
                .width(200)
                .action(DialogAction.customClick((response, audience) -> acceptPlayer(audience, disconnectCallback), CALLBACK_OPTIONS))
                .build();

        return Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(text(title, NamedTextColor.DARK_AQUA))
                        .canCloseWithEscape(false)
                        .pause(false)
                        .afterAction(DialogBase.DialogAfterAction.NONE)
                        .body(body)
                        .inputs(List.of(DialogInput.text(CODE_INPUT, text("Authentication code", NamedTextColor.WHITE))
                                .width(200)
                                .maxLength(6)
                                .build()))
                        .build())
                .type(DialogType.multiAction(List.of(submit, switchToChat), disconnect, 1)));
    }

    private void submit(Audience audience, String code, BiConsumer<Player, String> callback)
    {
        if (audience instanceof Player player)
        {
            callback.accept(player, code);
        }
    }

    private void acceptPlayer(Audience audience, Consumer<Player> callback)
    {
        if (audience instanceof Player player)
        {
            callback.accept(player);
        }
    }

    private void addError(List<DialogBody> body, String error)
    {
        if (error != null && !error.isBlank())
        {
            body.add(DialogBody.plainMessage(text(error, NamedTextColor.RED)));
        }
    }

    private void sendChatError(Player player, String error)
    {
        if (error != null && !error.isBlank())
        {
            player.sendMessage(text(error, NamedTextColor.RED));
        }
    }

    private String group(String secret)
    {
        return secret.replaceAll("(.{4})(?!$)", "$1 ");
    }

    private Component text(String value, NamedTextColor color)
    {
        return Component.text(value, color).decoration(TextDecoration.ITALIC, false);
    }
}
