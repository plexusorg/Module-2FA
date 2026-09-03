package dev.plex.auth;

import com.destroystokyo.paper.profile.PlayerProfile;
import dev.plex.api.player.PlexPlayerView;
import dev.plex.api.player.PlayersApi;
import dev.plex.integration.PermissionDataBridge;
import dev.plex.storage.PremiumIdentityRepository;
import dev.plex.storage.TwoFactorRepository;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;

public final class AccountClassifier
{
    private static final int LOOKUP_TIMEOUT_SECONDS = 10;
    private final PlayersApi players;
    private final PremiumIdentityRepository identityRepository;
    private final TwoFactorRepository twoFactorRepository;
    private final PermissionDataBridge permissionDataBridge;
    private final Map<UUID, LoginEvidence> loginEvidence = new ConcurrentHashMap<>();

    public AccountClassifier(
            PlayersApi players,
            PremiumIdentityRepository identityRepository,
            TwoFactorRepository twoFactorRepository,
            PermissionDataBridge permissionDataBridge)
    {
        this.players = players;
        this.identityRepository = identityRepository;
        this.twoFactorRepository = twoFactorRepository;
        this.permissionDataBridge = permissionDataBridge;
    }

    public void capture(AsyncPlayerPreLoginEvent event)
    {
        UUID initialUuid = event.getUniqueId();
        PlayerProfile unsafeProfile = event.getConnection().getUnsafeProfile();
        PlayerProfile authenticatedProfile = event.getConnection().getAuthenticatedProfile();
        UUID mappedIdentityUuid = null;
        if (authenticatedProfile != null && authenticatedProfile.getId() != null)
        {
            event.setPlayerProfile(authenticatedProfile.clone());
        }
        else if (unsafeProfile != null && unsafeProfile.getId() != null && unsafeProfile.getId().version() == 4)
        {
            event.setPlayerProfile(unsafeProfile.clone());
        }
        else
        {
            try
            {
                mappedIdentityUuid = await(identityRepository.find(event.getName())).orElse(null);
                if (mappedIdentityUuid == null)
                {
                    mappedIdentityUuid = resolveStoredIdentity(event.getName());
                }
            }
            catch (CompletionException exception)
            {
                throw new IllegalStateException("Unable to resolve premium identity", exception.getCause());
            }
            if (mappedIdentityUuid != null)
            {
                event.setPlayerProfile(Bukkit.createProfileExact(mappedIdentityUuid, event.getName()));
            }
        }
        UUID finalUuid = event.getUniqueId();
        boolean twoFactorConfigured = await(twoFactorRepository.exists(finalUuid));
        if (mappedIdentityUuid == null && finalUuid.version() == 4 && twoFactorConfigured)
        {
            await(identityRepository.save(event.getName(), finalUuid));
        }
        loginEvidence.put(event.getUniqueId(), new LoginEvidence(
                initialUuid,
                unsafeProfile == null ? null : unsafeProfile.getId(),
                authenticatedProfile == null ? null : authenticatedProfile.getId(),
                mappedIdentityUuid,
                twoFactorConfigured));
    }

    public AccountClassification classify(Player player)
    {
        LoginEvidence evidence = loginEvidence.remove(player.getUniqueId());
        boolean proxyOnlineMode = Bukkit.getServerConfig().isProxyOnlineMode();
        UUID offlineUuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + player.getName()).getBytes(StandardCharsets.UTF_8));
        UUID clientClaimedUuid = evidence == null ? null : evidence.clientClaimedUuid();
        UUID authenticatedUuid = evidence == null ? null : evidence.authenticatedUuid();
        UUID initialUuid = evidence == null ? null : evidence.initialUuid();
        UUID mappedIdentityUuid = evidence == null ? null : evidence.mappedIdentityUuid();
        boolean twoFactorConfigured = evidence != null && evidence.twoFactorConfigured();

        AccountType accountType = AccountType.fromLoginEvidence(
                player.getUniqueId(), offlineUuid, clientClaimedUuid, authenticatedUuid, evidence != null);

        return new AccountClassification(
                accountType,
                proxyOnlineMode,
                offlineUuid,
                initialUuid,
                clientClaimedUuid,
                authenticatedUuid,
                mappedIdentityUuid,
                twoFactorConfigured,
                evidence != null);
    }

    public void forget(UUID playerUuid)
    {
        loginEvidence.remove(playerUuid);
    }

    private UUID resolveStoredIdentity(String username)
    {
        UUID plexUuid = await(players.byName(username)).map(PlexPlayerView::uuid).orElse(null);
        if (isProtectedIdentityUuid(plexUuid))
        {
            savePremiumIdentity(username, plexUuid);
            return plexUuid;
        }

        UUID forwardLookup = await(permissionDataBridge.lookupUniqueId(username)).orElse(null);
        if (isProtectedIdentityUuid(forwardLookup))
        {
            savePremiumIdentity(username, forwardLookup);
            return forwardLookup;
        }

        List<UUID> protectedUuids = await(twoFactorRepository.findPlayerUuids()).stream()
                .sorted(java.util.Comparator.comparingInt(playerUuid -> playerUuid.version() == 4 ? 0 : 1))
                .toList();
        for (UUID candidate : protectedUuids)
        {
            if (candidate.version() != 3 && candidate.version() != 4)
            {
                continue;
            }
            Optional<String> storedUsername = await(permissionDataBridge.lookupUsername(candidate));
            if (storedUsername.isPresent() && storedUsername.get().equalsIgnoreCase(username))
            {
                savePremiumIdentity(username, candidate);
                return candidate;
            }
        }
        return null;
    }

    private boolean isProtectedIdentityUuid(UUID playerUuid)
    {
        return playerUuid != null
                && (playerUuid.version() == 3 || playerUuid.version() == 4)
                && (await(twoFactorRepository.exists(playerUuid))
                || await(permissionDataBridge.requiresTwoFactor(playerUuid)));
    }

    private void savePremiumIdentity(String username, UUID playerUuid)
    {
        if (playerUuid.version() == 4)
        {
            await(identityRepository.save(username, playerUuid));
        }
    }

    private static <T> T await(CompletableFuture<T> future)
    {
        return future.orTimeout(LOOKUP_TIMEOUT_SECONDS, TimeUnit.SECONDS).join();
    }
}
