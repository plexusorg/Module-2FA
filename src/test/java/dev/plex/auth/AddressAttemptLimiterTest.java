package dev.plex.auth;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class AddressAttemptLimiterTest
{
    @Test
    void lockoutSurvivesReconnectButDoesNotAffectAnotherAddress()
    {
        AddressAttemptLimiter limiter = new AddressAttemptLimiter();
        UUID playerUuid = UUID.randomUUID();
        long currentTime = 1_000_000;

        assertFalse(limiter.recordFailure(playerUuid, "192.0.2.1", currentTime, 3, 300_000, 300_000).locked(currentTime));
        assertFalse(limiter.recordFailure(playerUuid, "192.0.2.1", currentTime + 1, 3, 300_000, 300_000).locked(currentTime + 1));
        assertTrue(limiter.recordFailure(playerUuid, "192.0.2.1", currentTime + 2, 3, 300_000, 300_000).locked(currentTime + 2));

        assertTrue(limiter.locked(playerUuid, "192.0.2.1", currentTime + 3));
        assertFalse(limiter.locked(playerUuid, "198.51.100.2", currentTime + 3));
        assertFalse(limiter.locked(playerUuid, "192.0.2.1", currentTime + 300_003));
    }
}
