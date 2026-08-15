package dev.plex.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class SqlIdentifierTest
{
    @Test
    void quotesNumericPrefixForSqliteAndPostgres()
    {
        assertEquals("\"2fa_accounts\"", SqlIdentifier.quote("2fa_accounts", "\""));
    }

    @Test
    void quotesNumericPrefixForMariaDb()
    {
        assertEquals("`2fa_accounts`", SqlIdentifier.quote("2fa_accounts", "`"));
    }

    @Test
    void rejectsUnsafeIdentifiers()
    {
        assertThrows(IllegalArgumentException.class, () -> SqlIdentifier.quote("accounts; DROP TABLE players", "\""));
    }

    @Test
    void requiresQuotesForNumericPrefixes()
    {
        assertThrows(IllegalStateException.class, () -> SqlIdentifier.quote("2fa_accounts", " "));
        assertEquals("module_accounts", SqlIdentifier.quote("module_accounts", " "));
    }
}
