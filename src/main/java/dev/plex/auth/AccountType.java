package dev.plex.auth;

import java.util.UUID;

public enum AccountType
{
    AUTHENTICATED,
    UNVERIFIED_PREMIUM_CLAIM,
    CRACKED,
    UNKNOWN;

    static AccountType fromLoginEvidence(
            UUID playerUuid,
            UUID offlineUuid,
            UUID clientClaimedUuid,
            UUID authenticatedUuid,
            boolean loginEvidencePresent)
    {
        if (authenticatedUuid != null && authenticatedUuid.equals(playerUuid))
        {
            return AUTHENTICATED;
        }
        if (!loginEvidencePresent)
        {
            return UNKNOWN;
        }
        if (clientClaimedUuid != null
                && clientClaimedUuid.version() == 4
                && !clientClaimedUuid.equals(offlineUuid))
        {
            return UNVERIFIED_PREMIUM_CLAIM;
        }
        return CRACKED;
    }
}
