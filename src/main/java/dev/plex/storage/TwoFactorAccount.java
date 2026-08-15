package dev.plex.storage;

import java.util.UUID;

public record TwoFactorAccount(UUID playerUuid, byte[] secret, long lastUsedStep, AuthenticationThrottle throttle)
{
}
