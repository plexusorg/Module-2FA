package dev.plex.storage;

import java.sql.SQLException;
import java.util.regex.Pattern;
import org.jdbi.v3.core.Jdbi;

final class SqlIdentifier
{
    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("[A-Za-z0-9_]+");
    private static final Pattern UNQUOTED_IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private SqlIdentifier()
    {
    }

    static String quote(Jdbi jdbi, String identifier)
    {
        try
        {
            return jdbi.withHandle(handle -> quote(identifier, handle.getConnection().getMetaData().getIdentifierQuoteString()));
        }
        catch (SQLException exception)
        {
            throw new IllegalStateException("Unable to determine database identifier quoting", exception);
        }
    }

    static String quote(String identifier, String quote)
    {
        if (!SAFE_IDENTIFIER.matcher(identifier).matches())
        {
            throw new IllegalArgumentException("Unsafe SQL identifier: " + identifier);
        }

        String normalizedQuote = quote == null ? "" : quote.trim();
        if (normalizedQuote.isEmpty())
        {
            if (UNQUOTED_IDENTIFIER.matcher(identifier).matches())
            {
                return identifier;
            }
            throw new IllegalStateException("Database does not support quoting the SQL identifier: " + identifier);
        }
        return normalizedQuote + identifier + normalizedQuote;
    }
}
