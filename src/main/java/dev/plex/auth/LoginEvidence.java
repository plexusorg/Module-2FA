package dev.plex.auth;

import java.util.UUID;

public record LoginEvidence(
        UUID initialUuid,
        UUID clientClaimedUuid,
        UUID authenticatedUuid,
        UUID mappedIdentityUuid,
        boolean twoFactorConfigured)
{
}
