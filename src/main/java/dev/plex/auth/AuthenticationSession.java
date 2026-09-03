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

    public synchronized boolean authorizeEnrollment()
    {
        if (phase != AuthenticationPhase.AWAITING_ENROLLMENT_APPROVAL)
        {
            return false;
        }
        enrollmentAuthorized = true;
        return true;
    }

    public boolean chatInputMode()
    {
        return chatInputMode;
    }

    public synchronized AuthenticationPhase enableChatInput()
    {
        if (phase != AuthenticationPhase.ENROLLING && phase != AuthenticationPhase.VERIFYING)
        {
            return null;
        }
        chatInputMode = true;
        return phase;
    }

    public int recordFailedAttempt()
    {
        return attempts.incrementAndGet();
    }

    public AuthenticationPhase phase()
    {
        return phase;
    }

    public synchronized boolean transition(AuthenticationPhase expected, AuthenticationPhase next)
    {
        if (phase != expected)
        {
            return false;
        }
        phase = next;
        return true;
    }

    public synchronized boolean terminate()
    {
        if (phase == AuthenticationPhase.TERMINATING)
        {
            return false;
        }
        phase = AuthenticationPhase.TERMINATING;
        return true;
    }

    public synchronized void awaitEnrollmentApproval()
    {
        phase = AuthenticationPhase.AWAITING_ENROLLMENT_APPROVAL;
    }

    public synchronized void beginEnrollment(byte[] secret)
    {
        pendingSecret = secret;
        phase = AuthenticationPhase.ENROLLING;
    }

    public synchronized void beginVerification(TwoFactorAccount account)
    {
        this.account = account;
        phase = AuthenticationPhase.VERIFYING;
    }

    public synchronized void beginReset()
    {
        phase = AuthenticationPhase.RESETTING;
    }

    public synchronized boolean prepareFailedVerification()
    {
        if (phase != AuthenticationPhase.VERIFYING && phase != AuthenticationPhase.PROCESSING)
        {
            return false;
        }
        phase = AuthenticationPhase.PROCESSING;
        return true;
    }

    public byte[] pendingSecret()
    {
        return pendingSecret;
    }

    public TwoFactorAccount account()
    {
        return account;
    }

}
