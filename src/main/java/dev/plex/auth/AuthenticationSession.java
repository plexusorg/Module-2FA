package dev.plex.auth;

import dev.plex.storage.TwoFactorAccount;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.entity.Player;

final class AuthenticationSession
{
    private final Player player;
    private final UUID crackedUuid;
    private final UUID premiumUuid;
    private final AtomicInteger attempts = new AtomicInteger();
    private volatile AuthenticationPhase phase = AuthenticationPhase.LOADING;
    private volatile boolean enrollmentAuthorized;
    private volatile boolean chatInputMode;
    private volatile byte[] pendingSecret;
    private volatile TwoFactorAccount account;

    AuthenticationSession(Player player, boolean enrollmentAuthorized)
    {
        this(player, enrollmentAuthorized, null);
    }

    AuthenticationSession(Player player, boolean enrollmentAuthorized, AccountClassification classification)
    {
        this.player = player;
        this.enrollmentAuthorized = enrollmentAuthorized;
        if (classification != null
                && classification.accountType() == AccountType.CRACKED
                && player.getUniqueId().version() == 4
                && !player.getUniqueId().equals(classification.offlineUuid()))
        {
            this.crackedUuid = classification.offlineUuid();
            this.premiumUuid = player.getUniqueId();
        }
        else
        {
            this.crackedUuid = null;
            this.premiumUuid = null;
        }
    }

    public Player player()
    {
        return player;
    }

    public boolean requiresIdentityReconciliation()
    {
        return crackedUuid != null && premiumUuid != null;
    }

    public UUID crackedUuid()
    {
        return crackedUuid;
    }

    public UUID premiumUuid()
    {
        return premiumUuid;
    }

    public boolean enrollmentAuthorized()
    {
        return enrollmentAuthorized;
    }

    public void authorizeEnrollment()
    {
        enrollmentAuthorized = true;
    }

    public boolean chatInputMode()
    {
        return chatInputMode;
    }

    public void chatInputMode(boolean chatInputMode)
    {
        this.chatInputMode = chatInputMode;
    }

    public int recordFailedAttempt()
    {
        return attempts.incrementAndGet();
    }

    public AuthenticationPhase phase()
    {
        return phase;
    }

    public void phase(AuthenticationPhase phase)
    {
        this.phase = phase;
    }

    public byte[] pendingSecret()
    {
        return pendingSecret;
    }

    public void pendingSecret(byte[] pendingSecret)
    {
        this.pendingSecret = pendingSecret;
    }

    public TwoFactorAccount account()
    {
        return account;
    }

    public void account(TwoFactorAccount account)
    {
        this.account = account;
    }
}
