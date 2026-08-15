package dev.plex.auth;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

final class AddressAttemptLimiter
{
    private final Map<Key, State> attempts = new ConcurrentHashMap<>();

    State recordFailure(UUID playerUuid, String address, long currentTime, int maximumAttempts, long windowMillis, long lockoutMillis)
    {
        Key key = new Key(playerUuid, address);
        return attempts.compute(key, (ignored, current) ->
        {
            if (current != null && current.locked(currentTime))
            {
                return current;
            }
            boolean windowExpired = current == null || current.lastFailedAt() == 0 || currentTime - current.lastFailedAt() > windowMillis;
            int failedAttempts = windowExpired ? 1 : current.failedAttempts() + 1;
            long lockedUntil = failedAttempts >= maximumAttempts ? currentTime + lockoutMillis : 0;
            return new State(failedAttempts, lockedUntil, currentTime);
        });
    }

    boolean locked(UUID playerUuid, String address, long currentTime)
    {
        Key key = new Key(playerUuid, address);
        State state = attempts.get(key);
        if (state == null)
        {
            return false;
        }
        if (state.locked(currentTime))
        {
            return true;
        }
        if (state.lockedUntil() > 0)
        {
            attempts.remove(key, state);
        }
        return false;
    }

    void clear(UUID playerUuid, String address)
    {
        attempts.remove(new Key(playerUuid, address));
    }

    record State(int failedAttempts, long lockedUntil, long lastFailedAt)
    {
        boolean locked(long currentTime)
        {
            return lockedUntil > currentTime;
        }
    }

    private record Key(UUID playerUuid, String address)
    {
    }
}
