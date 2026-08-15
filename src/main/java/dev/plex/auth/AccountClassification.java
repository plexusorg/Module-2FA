package dev.plex.auth;

import java.util.UUID;

public record AccountClassification(
        AccountType accountType,
        boolean proxyOnlineMode,
        UUID offlineUuid,
        UUID initialUuid,
        UUID clientClaimedUuid,
        UUID authenticatedUuid,
        UUID mappedIdentityUuid,
        boolean twoFactorConfigured,
        boolean loginEvidencePresent)
{
    public boolean requiresTwoFactor(boolean required, boolean requiredForCracked)
    {
        return required || (accountType == AccountType.CRACKED && (requiredForCracked || twoFactorConfigured));
    }
}
