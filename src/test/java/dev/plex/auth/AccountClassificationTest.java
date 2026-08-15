package dev.plex.auth;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AccountClassificationTest
{
    @Test
    void crackedOnlyPermissionAppliesOnlyToCrackedAccounts()
    {
        assertTrue(classification(AccountType.CRACKED).requiresTwoFactor(false, true));
        assertFalse(classification(AccountType.UNVERIFIED_PREMIUM_CLAIM).requiresTwoFactor(false, true));
        assertFalse(classification(AccountType.AUTHENTICATED).requiresTwoFactor(false, true));
        assertFalse(classification(AccountType.UNKNOWN).requiresTwoFactor(false, true));
    }

    @Test
    void requiredPermissionAppliesToEveryAccountType()
    {
        for (AccountType accountType : AccountType.values())
        {
            assertTrue(classification(accountType).requiresTwoFactor(true, false));
        }
    }

    @Test
    void configuredCrackedAccountRequiresAuthentication()
    {
        AccountClassification classification = new AccountClassification(
                AccountType.CRACKED, false, null, null, null, null, null, true, true);

        assertTrue(classification.requiresTwoFactor(false, false));
    }

    @Test
    void configuredAccountDoesNotForcePremiumAuthenticationWithoutPermission()
    {
        AccountClassification classification = new AccountClassification(
                AccountType.AUTHENTICATED, true, null, null, null, null, null, true, true);

        assertFalse(classification.requiresTwoFactor(false, false));
    }

    @Test
    void missingLoginEvidenceIsUnknownRatherThanCracked()
    {
        UUID offlineUuid = offlineUuid();

        assertEquals(AccountType.UNKNOWN, AccountType.fromLoginEvidence(
                offlineUuid, offlineUuid, null, null, false));
    }

    @Test
    void capturedLoginWithoutClientUuidIsCracked()
    {
        UUID offlineUuid = offlineUuid();

        assertEquals(AccountType.CRACKED, AccountType.fromLoginEvidence(
                offlineUuid, offlineUuid, null, null, true));
        UUID premiumUuid = UUID.fromString("f5cd54c4-3a24-4213-9a56-c06c49594dff");
        assertEquals(AccountType.CRACKED, AccountType.fromLoginEvidence(
                premiumUuid, offlineUuid, null, null, true));
        UUID historicalOfflineUuid = UUID.fromString("a8347a39-3380-3e12-8827-7dba9a03cfcf");
        assertEquals(AccountType.CRACKED, AccountType.fromLoginEvidence(
                historicalOfflineUuid, offlineUuid, null, null, true));
    }

    @Test
    void premiumClaimAndOfflineClaimRemainDistinct()
    {
        UUID offlineUuid = offlineUuid();
        UUID premiumClaim = UUID.fromString("f5cd54c4-3a24-4213-9a56-c06c49594dff");

        assertEquals(AccountType.UNVERIFIED_PREMIUM_CLAIM, AccountType.fromLoginEvidence(
                offlineUuid, offlineUuid, premiumClaim, null, true));
        assertEquals(AccountType.UNVERIFIED_PREMIUM_CLAIM, AccountType.fromLoginEvidence(
                premiumClaim, offlineUuid, premiumClaim, null, true));
        assertEquals(AccountType.CRACKED, AccountType.fromLoginEvidence(
                offlineUuid, offlineUuid, offlineUuid, null, true));
    }

    private AccountClassification classification(AccountType accountType)
    {
        return new AccountClassification(accountType, false, null, null, null, null, null, false, true);
    }

    private UUID offlineUuid()
    {
        return UUID.nameUUIDFromBytes("OfflinePlayer:Taahh".getBytes(StandardCharsets.UTF_8));
    }
}
