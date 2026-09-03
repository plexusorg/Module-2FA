package dev.plex.auth;

import dev.plex.TwoFactorModule;
import dev.plex.TwoFactorPermissions;
import dev.plex.crypto.TotpService;
import dev.plex.dialog.TwoFactorDialogService;
import dev.plex.integration.PermissionDataBridge;
import dev.plex.storage.AuthenticationThrottle;
import dev.plex.storage.PremiumIdentityRepository;
import dev.plex.storage.TwoFactorAccount;
import dev.plex.storage.TwoFactorRepository;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;

public final class AuthenticationManager
{
    private static final int MAX_ATTEMPTS = 5;
    private static final long AUTHENTICATION_TIMEOUT_TICKS = 20L * 120L;
    private static final long FAILURE_WINDOW_MILLIS = 5L * 60L * 1000L;
    private static final long LOCKOUT_MILLIS = 5L * 60L * 1000L;
    private static final int MAX_ADDRESS_ATTEMPTS = 3;

    private final TwoFactorModule module;
    private final AccountClassifier classifier;
    private final TwoFactorRepository repository;
    private final PremiumIdentityRepository identityRepository;
    private final PermissionDataBridge permissionDataBridge;
    private final TotpService totpService;
    private final TwoFactorDialogService dialogService;
    private final Map<UUID, AuthenticationSession> sessions = new ConcurrentHashMap<>();
    private final Map<UUID, Player> authenticatedPlayers = new ConcurrentHashMap<>();
    private final AddressAttemptLimiter addressAttemptLimiter = new AddressAttemptLimiter();

    public AuthenticationManager(
            TwoFactorModule module,
            AccountClassifier classifier,
            TwoFactorRepository repository,
            PremiumIdentityRepository identityRepository,
            PermissionDataBridge permissionDataBridge,
            TotpService totpService,
            TwoFactorDialogService dialogService)
    {
        this.module = module;
        this.classifier = classifier;
        this.repository = repository;
        this.identityRepository = identityRepository;
        this.permissionDataBridge = permissionDataBridge;
        this.totpService = totpService;
        this.dialogService = dialogService;
    }

    public void captureLoginEvidence(AsyncPlayerPreLoginEvent event)
    {
        try
        {
            classifier.capture(event);
        }
        catch (RuntimeException exception)
        {
            module.getLogger().error("Unable to resolve premium identity for {}", event.getName(), exception);
            event.disallow(
                    AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                    Component.text("Two-factor identity verification is temporarily unavailable.", NamedTextColor.RED));
        }
    }

    public void handleJoin(Player player)
    {
        AccountClassification classification = classifier.classify(player);
        boolean required = player.hasPermission(TwoFactorPermissions.REQUIRED);
        boolean requiredForCracked = player.hasPermission(TwoFactorPermissions.REQUIRED_CRACKED);
        boolean requireTwoFactor = classification.requiresTwoFactor(required, requiredForCracked);
        if (classification.accountType() == AccountType.AUTHENTICATED)
        {
            authenticatedPlayers.put(player.getUniqueId(), player);
        }

        module.getLogger().info(
                "Account detector: player={}, uuid={}, type={}, proxyOnlineMode={}, offlineUuid={}, initialUuid={}, clientClaimedUuid={}, clientClaimedVersion={}, authenticatedUuid={}, mappedIdentityUuid={}, loginEvidencePresent={}, twoFactorConfigured={}, required={}, requiredCracked={}, require2FA={}",
                player.getName(),
                player.getUniqueId(),
                classification.accountType(),
                classification.proxyOnlineMode(),
                classification.offlineUuid(),
                classification.initialUuid(),
                classification.clientClaimedUuid(),
                classification.clientClaimedUuid() == null ? null : classification.clientClaimedUuid().version(),
                classification.authenticatedUuid(),
                classification.mappedIdentityUuid(),
                classification.loginEvidencePresent(),
                classification.twoFactorConfigured(),
                required,
                requiredForCracked,
                requireTwoFactor);

        if (!requireTwoFactor)
        {
            return;
        }

        AuthenticationSession session = new AuthenticationSession(
                player,
                classification.accountType() == AccountType.AUTHENTICATED || required || requiredForCracked,
                classification);
        sessions.put(player.getUniqueId(), session);
        player.leaveVehicle();
        if (addressAttemptLimiter.locked(player.getUniqueId(), address(player), System.currentTimeMillis()))
        {
            kick(player, session, Component.text(
                    "Too many failed authentication attempts from your address. Try again later.", NamedTextColor.RED));
            return;
        }
        scheduleTimeout(player, session);
        repository.find(player.getUniqueId()).whenComplete((account, throwable) -> onPlayerThread(player, () ->
        {
            if (!isCurrent(player, session) || session.phase() != AuthenticationPhase.LOADING)
            {
                return;
            }
            if (throwable != null)
            {
                storageFailure(player, session, "load", throwable);
                return;
            }
            beginPrompt(player, session, account);
        }));
    }

    public boolean isLocked(UUID playerUuid)
    {
        return sessions.containsKey(playerUuid);
    }

    public void submitChatInput(Player player, String code)
    {
        AuthenticationSession session = sessions.get(player.getUniqueId());
        if (session == null || !session.chatInputMode())
        {
            return;
        }
        onPlayerThread(player, () ->
        {
            if (!isCurrent(player, session) || !session.chatInputMode())
            {
                return;
            }
            String normalizedCode = code.trim();
            if (!normalizedCode.matches("\\d{6}"))
            {
                player.sendMessage(Component.text("Enter exactly six digits.", NamedTextColor.RED));
                return;
            }
            if (session.phase() == AuthenticationPhase.ENROLLING)
            {
                submitEnrollment(player, session, normalizedCode);
            }
            else if (session.phase() == AuthenticationPhase.VERIFYING)
            {
                submitVerification(player, session, normalizedCode);
            }
        });
    }

    public void handleQuit(Player player)
    {
        AuthenticationSession session = sessions.get(player.getUniqueId());
        if (session != null && session.player() == player && sessions.remove(player.getUniqueId(), session))
        {
            reconcileIdentity(session).whenComplete((ignored, throwable) -> logReconciliationFailure(player, throwable));
        }
        authenticatedPlayers.remove(player.getUniqueId(), player);
        classifier.forget(player.getUniqueId());
    }

    public void forgetLoginEvidence(UUID playerUuid)
    {
        classifier.forget(playerUuid);
    }

    public CompletableFuture<Void> reset(UUID playerUuid, Player onlinePlayer)
    {
        if (onlinePlayer != null && !onlinePlayer.getUniqueId().equals(playerUuid))
        {
            throw new IllegalArgumentException("Online player does not match reset UUID");
        }
        AuthenticationSession session = onlinePlayer == null ? sessions.get(playerUuid) : sessions.compute(playerUuid, (uuid, current) ->
        {
            if (current != null && current.player() == onlinePlayer)
            {
                return current;
            }
            return new AuthenticationSession(onlinePlayer, false);
        });

        CompletableFuture<Void> deletion;
        if (session == null)
        {
            deletion = repository.delete(playerUuid).thenCompose(ignored -> identityRepository.delete(playerUuid));
        }
        else
        {
            synchronized (session)
            {
                session.beginReset();
                deletion = repository.delete(playerUuid).thenCompose(ignored -> identityRepository.delete(playerUuid));
            }
            deletion.whenComplete((unused, throwable) -> onPlayerThread(session.player(), () ->
            {
                kick(session.player(), session, Component.text(
                        throwable == null
                                ? "Your two-factor authentication was reset. Reconnect to enroll again."
                                : "Two-factor authentication reset failed. Reconnect before continuing.",
                        throwable == null ? NamedTextColor.YELLOW : NamedTextColor.RED));
            }));
        }
        return deletion;
    }

    public void setup(Player player)
    {
        AuthenticationSession existingSession = sessions.get(player.getUniqueId());
        if (existingSession != null)
        {
            reopen(player);
            return;
        }

        AuthenticationSession session = new AuthenticationSession(player, enrollmentAuthorized(player));
        if (sessions.putIfAbsent(player.getUniqueId(), session) != null)
        {
            reopen(player);
            return;
        }

        player.leaveVehicle();
        scheduleTimeout(player, session);
        repository.find(player.getUniqueId()).whenComplete((account, throwable) -> onPlayerThread(player, () ->
        {
            if (!isCurrent(player, session) || session.phase() != AuthenticationPhase.LOADING)
            {
                return;
            }
            if (throwable != null)
            {
                storageFailure(player, session, "load", throwable);
                return;
            }
            if (account.isPresent())
            {
                sessions.remove(player.getUniqueId(), session);
                player.sendMessage(Component.text(
                        "Two-factor authentication is already configured. Ask an administrator to reset it before reenrolling.",
                        NamedTextColor.YELLOW));
                return;
            }
            beginPrompt(player, session, Optional.empty());
        }));
    }

    public void reopen(Player player)
    {
        AuthenticationSession session = sessions.get(player.getUniqueId());
        if (session == null)
        {
            return;
        }
        if (session.phase() == AuthenticationPhase.ENROLLING)
        {
            openEnrollment(player, session, null);
        }
        else if (session.phase() == AuthenticationPhase.VERIFYING)
        {
            openVerification(player, session, null);
        }
        else if (session.phase() == AuthenticationPhase.AWAITING_ENROLLMENT_APPROVAL)
        {
            openEnrollmentApproval(player);
        }
    }

    public boolean authorizeEnrollment(Player player)
    {
        AuthenticationSession session = sessions.get(player.getUniqueId());
        if (session == null)
        {
            return false;
        }
        synchronized (session)
        {
            if (!isCurrent(player, session) || !session.authorizeEnrollment())
            {
                return false;
            }
            startEnrollment(player, session);
            return true;
        }
    }

    public CompletableFuture<Void> shutdown()
    {
        List<AuthenticationSession> pending = new ArrayList<>();
        for (AuthenticationSession session : List.copyOf(sessions.values()))
        {
            if (!sessions.remove(session.player().getUniqueId(), session))
            {
                continue;
            }
            pending.add(session);
        }

        Component reason = Component.text(
                "Two-factor authentication was disabled while your login was pending.", NamedTextColor.RED);
        pending.forEach(session -> module.kickPlayerOnShutdown(session.player(), reason));
        authenticatedPlayers.clear();

        return CompletableFuture.runAsync(() -> reconcileForShutdown(pending), module.executor());
    }

    private void reconcileForShutdown(List<AuthenticationSession> pending)
    {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        for (AuthenticationSession session : pending)
        {
            if (!session.requiresIdentityReconciliation())
            {
                continue;
            }
            try
            {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0)
                {
                    module.getLogger().warn("Timed out waiting for LuckPerms reconciliation during two-factor shutdown");
                    return;
                }
                permissionDataBridge.reconcileForShutdown(
                        session.crackedUuid(), session.premiumUuid(), session.player().getName(), remaining);
            }
            catch (InterruptedException exception)
            {
                Thread.currentThread().interrupt();
                logReconciliationFailure(session.player(), exception);
                return;
            }
            catch (Exception exception)
            {
                logReconciliationFailure(session.player(), exception);
            }
        }
    }

    private void beginPrompt(Player player, AuthenticationSession session, Optional<TwoFactorAccount> account)
    {
        if (account.isPresent())
        {
            TwoFactorAccount existingAccount = account.get();
            long currentTime = System.currentTimeMillis();
            if (existingAccount.throttle().locked(currentTime))
            {
                long remainingSeconds = Math.max(1, (existingAccount.throttle().lockedUntil() - currentTime + 999) / 1000);
                kick(player, session, Component.text(
                        "Too many failed authentication attempts. Try again in " + remainingSeconds + " seconds.",
                        NamedTextColor.RED));
                return;
            }
            session.beginVerification(existingAccount);
            openVerification(player, session, null);
            return;
        }

        if (!session.enrollmentAuthorized())
        {
            session.awaitEnrollmentApproval();
            openEnrollmentApproval(player);
            module.getLogger().warn("Player {} requires administrator approval before first-time 2FA enrollment", player.getName());
            return;
        }
        startEnrollment(player, session);
    }

    private void startEnrollment(Player player, AuthenticationSession session)
    {
        session.beginEnrollment(totpService.generateSecret());
        openEnrollment(player, session, null);
    }

    private void openEnrollmentApproval(Player player)
    {
        dialogService.openEnrollmentApproval(player, this::disconnect);
    }

    private void openEnrollment(Player player, AuthenticationSession session, String error)
    {
        if (session.chatInputMode())
        {
            dialogService.sendEnrollmentChatPrompt(player, session.pendingSecret(), error);
            return;
        }
        dialogService.openEnrollment(
                player,
                session.pendingSecret(),
                error,
                (viewer, code) -> onPlayerThread(viewer, () -> submitEnrollment(viewer, session, code)),
                viewer -> onPlayerThread(viewer, () -> switchToChat(viewer, session)),
                this::disconnect);
    }

    private void openVerification(Player player, AuthenticationSession session, String error)
    {
        if (session.chatInputMode())
        {
            dialogService.sendVerificationChatPrompt(player, error);
            return;
        }
        dialogService.openVerification(
                player,
                error,
                (viewer, code) -> onPlayerThread(viewer, () -> submitVerification(viewer, session, code)),
                viewer -> onPlayerThread(viewer, () -> switchToChat(viewer, session)),
                this::disconnect);
    }

    private void switchToChat(Player player, AuthenticationSession session)
    {
        if (!isCurrent(player, session))
        {
            return;
        }
        AuthenticationPhase phase = session.enableChatInput();
        if (phase == null)
        {
            return;
        }
        player.closeDialog();
        if (phase == AuthenticationPhase.ENROLLING)
        {
            dialogService.sendEnrollmentChatPrompt(player, session.pendingSecret(), null);
        }
        else
        {
            dialogService.sendVerificationChatPrompt(player, null);
        }
    }

    private void submitEnrollment(Player player, AuthenticationSession session, String code)
    {
        CompletableFuture<Void> creation;
        synchronized (session)
        {
            if (!isCurrent(player, session) || session.phase() != AuthenticationPhase.ENROLLING)
            {
                return;
            }

            OptionalLong matchingStep = totpService.findMatchingStep(session.pendingSecret(), code);
            if (matchingStep.isEmpty())
            {
                failedAttempt(player, session, AuthenticationPhase.ENROLLING, "That authentication code is invalid.");
                return;
            }

            if (!session.transition(AuthenticationPhase.ENROLLING, AuthenticationPhase.PROCESSING))
            {
                return;
            }
            creation = repository.create(player.getUniqueId(), session.pendingSecret(), matchingStep.getAsLong());
        }

        creation.whenComplete((unused, throwable) -> onPlayerThread(player, () ->
        {
            if (!isCurrent(player, session) || session.phase() != AuthenticationPhase.PROCESSING)
            {
                return;
            }
            if (throwable != null)
            {
                storageFailure(player, session, "create", throwable);
                return;
            }
            complete(player, session, "Two-factor authentication has been enabled.");
        }));
    }

    private void submitVerification(Player player, AuthenticationSession session, String code)
    {
        CompletableFuture<Boolean> consumption;
        synchronized (session)
        {
            if (!isCurrent(player, session) || session.phase() != AuthenticationPhase.VERIFYING)
            {
                return;
            }

            TwoFactorAccount account = session.account();
            OptionalLong matchingStep = totpService.findMatchingStep(account.secret(), code, account.lastUsedStep());
            if (matchingStep.isEmpty())
            {
                failedVerificationAttempt(player, session, "That authentication code is invalid.");
                return;
            }

            if (!session.transition(AuthenticationPhase.VERIFYING, AuthenticationPhase.PROCESSING))
            {
                return;
            }
            consumption = repository.consumeStep(player.getUniqueId(), matchingStep.getAsLong());
        }

        consumption.whenComplete((consumed, throwable) -> onPlayerThread(player, () ->
        {
            if (!isCurrent(player, session) || session.phase() != AuthenticationPhase.PROCESSING)
            {
                return;
            }
            if (throwable != null)
            {
                storageFailure(player, session, "verify", throwable);
                return;
            }
            if (!consumed)
            {
                failedVerificationAttempt(player, session, "That authentication code has already been used.");
                return;
            }
            complete(player, session, "Authentication successful.");
        }));
    }

    private void failedAttempt(Player player, AuthenticationSession session, AuthenticationPhase phase, String message)
    {
        if (session.recordFailedAttempt() >= MAX_ATTEMPTS)
        {
            kick(player, session, Component.text("Too many failed two-factor authentication attempts.", NamedTextColor.RED));
            return;
        }
        if (phase == AuthenticationPhase.ENROLLING)
        {
            openEnrollment(player, session, message);
        }
        else
        {
            openVerification(player, session, message);
        }
    }

    private void failedVerificationAttempt(Player player, AuthenticationSession session, String message)
    {
        if (!isCurrent(player, session))
        {
            return;
        }

        if (!session.prepareFailedVerification())
        {
            return;
        }
        AddressAttemptLimiter.State addressThrottle = addressAttemptLimiter.recordFailure(
                player.getUniqueId(),
                address(player),
                System.currentTimeMillis(),
                MAX_ADDRESS_ATTEMPTS,
                FAILURE_WINDOW_MILLIS,
                LOCKOUT_MILLIS);
        if (addressThrottle.locked(System.currentTimeMillis()))
        {
            kick(player, session, Component.text(
                    "Too many failed authentication attempts from your address. Try again in five minutes.",
                    NamedTextColor.RED));
            return;
        }
        repository.recordFailedAttempt(
                        player.getUniqueId(),
                        System.currentTimeMillis(),
                        MAX_ATTEMPTS,
                        FAILURE_WINDOW_MILLIS,
                        LOCKOUT_MILLIS)
                .whenComplete((throttle, throwable) -> onPlayerThread(player, () ->
                {
                    if (!isCurrent(player, session) || session.phase() != AuthenticationPhase.PROCESSING)
                    {
                        return;
                    }
                    if (throwable != null)
                    {
                        storageFailure(player, session, "record a failed attempt for", throwable);
                        return;
                    }
                    if (throttle.locked(System.currentTimeMillis()))
                    {
                        kick(player, session, Component.text(
                                "Too many failed authentication attempts. Try again in five minutes.",
                                NamedTextColor.RED));
                        return;
                    }

                    int remainingAttempts = MAX_ATTEMPTS - throttle.failedAttempts();
                    session.transition(AuthenticationPhase.PROCESSING, AuthenticationPhase.VERIFYING);
                    openVerification(player, session, message + " " + remainingAttempts + " attempts remain.");
                }));
    }

    private void complete(Player player, AuthenticationSession session, String message)
    {
        if (!isCurrent(player, session))
        {
            return;
        }
        finalizeIdentity(player, session).whenComplete((ignored, throwable) -> onPlayerThread(player, () ->
        {
            if (!isCurrent(player, session) || session.phase() != AuthenticationPhase.PROCESSING)
            {
                return;
            }
            if (throwable != null)
            {
                module.getLogger().error("Unable to finalize premium identity for {}", player.getName(), throwable);
                kick(player, session, Component.text(
                        "Two-factor identity synchronization failed. Reconnect before continuing.", NamedTextColor.RED));
                return;
            }
            if (!sessions.remove(player.getUniqueId(), session))
            {
                return;
            }
            player.closeDialog();
            addressAttemptLimiter.clear(player.getUniqueId(), address(player));
            player.sendMessage(Component.text(message, NamedTextColor.GREEN));
            module.getLogger().info("Player {} completed two-factor authentication", player.getName());
        }));
    }

    private void storageFailure(Player player, AuthenticationSession session, String operation, Throwable throwable)
    {
        module.getLogger().error("Unable to {} two-factor account for {}", operation, player.getName(), throwable);
        kick(player, session, Component.text("Two-factor authentication is temporarily unavailable.", NamedTextColor.RED));
    }

    private void scheduleTimeout(Player player, AuthenticationSession session)
    {
        module.ownTask(player.getScheduler().runDelayed(module.plugin(), ignored ->
        {
            if (isCurrent(player, session))
            {
                kick(player, session, Component.text("Two-factor authentication timed out.", NamedTextColor.RED));
            }
        }, null, AUTHENTICATION_TIMEOUT_TICKS));
    }

    private void kick(Player player, AuthenticationSession session, Component reason)
    {
        if (!session.terminate())
        {
            return;
        }
        reconcileIdentity(session).whenComplete((ignored, throwable) ->
        {
            logReconciliationFailure(player, throwable);
            onPlayerThread(player, () ->
            {
                if (sessions.remove(player.getUniqueId(), session))
                {
                    player.kick(reason);
                }
            });
        });
    }

    private boolean isCurrent(Player player, AuthenticationSession session)
    {
        return player.isConnected() && sessions.get(player.getUniqueId()) == session;
    }

    private boolean enrollmentAuthorized(Player player)
    {
        return authenticatedPlayers.get(player.getUniqueId()) == player
                || player.hasPermission(TwoFactorPermissions.REQUIRED)
                || player.hasPermission(TwoFactorPermissions.REQUIRED_CRACKED);
    }

    private void onPlayerThread(Player player, Runnable task)
    {
        module.ownTask(player.getScheduler().run(module.plugin(), ignored -> task.run(), null));
    }

    private void disconnect(Player player)
    {
        onPlayerThread(player, () ->
        {
            AuthenticationSession session = sessions.get(player.getUniqueId());
            if (session == null)
            {
                player.kick(Component.text("Disconnected during two-factor authentication.", NamedTextColor.YELLOW));
                return;
            }
            kick(player, session, Component.text(
                    "Disconnected during two-factor authentication.", NamedTextColor.YELLOW));
        });
    }

    private CompletableFuture<Void> finalizeIdentity(Player player, AuthenticationSession session)
    {
        CompletableFuture<Void> identitySave = player.getUniqueId().version() == 4
                ? identityRepository.save(player.getName(), player.getUniqueId())
                : CompletableFuture.completedFuture(null);
        return identitySave.thenCompose(ignored -> reconcileIdentity(session));
    }

    private CompletableFuture<Void> reconcileIdentity(AuthenticationSession session)
    {
        if (!session.requiresIdentityReconciliation())
        {
            return CompletableFuture.completedFuture(null);
        }
        return permissionDataBridge.reconcile(
                session.crackedUuid(), session.premiumUuid(), session.player().getName());
    }

    private void logReconciliationFailure(Player player, Throwable throwable)
    {
        if (throwable != null)
        {
            module.getLogger().error("Unable to reconcile LuckPerms UUID data for {}", player.getName(), throwable);
        }
    }

    private String address(Player player)
    {
        InetSocketAddress socketAddress = player.getAddress();
        if (socketAddress == null)
        {
            return "unknown";
        }
        return socketAddress.getAddress() == null
                ? socketAddress.getHostString()
                : socketAddress.getAddress().getHostAddress();
    }
}
