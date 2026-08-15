package dev.plex.storage;

public record AuthenticationThrottle(int failedAttempts, long lockedUntil, long lastFailedAt)
{
    public boolean locked(long currentTime)
    {
        return lockedUntil > currentTime;
    }
}
