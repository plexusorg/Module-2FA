package dev.plex.crypto;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Locale;
import java.util.OptionalLong;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class TotpService
{
    private static final int SECRET_LENGTH = 20;
    private static final long STEP_SECONDS = 30;
    private static final int CODE_MODULUS = 1_000_000;

    private final SecureRandom secureRandom;
    private final Clock clock;

    public TotpService()
    {
        this(new SecureRandom(), Clock.systemUTC());
    }

    TotpService(SecureRandom secureRandom, Clock clock)
    {
        this.secureRandom = secureRandom;
        this.clock = clock;
    }

    public byte[] generateSecret()
    {
        byte[] secret = new byte[SECRET_LENGTH];
        secureRandom.nextBytes(secret);
        return secret;
    }

    public OptionalLong findMatchingStep(byte[] secret, String submittedCode)
    {
        return findMatchingStep(secret, submittedCode, Long.MIN_VALUE);
    }

    public OptionalLong findMatchingStep(byte[] secret, String submittedCode, long minimumStepExclusive)
    {
        if (!validCode(submittedCode))
        {
            return OptionalLong.empty();
        }

        long currentStep = clock.instant().getEpochSecond() / STEP_SECONDS;
        byte[] submitted = submittedCode.getBytes(StandardCharsets.US_ASCII);
        for (long step = currentStep + 1; step >= currentStep - 1; step--)
        {
            if (step <= minimumStepExclusive)
            {
                continue;
            }
            byte[] expected = generateCode(secret, step).getBytes(StandardCharsets.US_ASCII);
            if (MessageDigest.isEqual(expected, submitted))
            {
                return OptionalLong.of(step);
            }
        }
        return OptionalLong.empty();
    }

    private boolean validCode(String code)
    {
        if (code == null || code.length() != 6)
        {
            return false;
        }
        for (int index = 0; index < code.length(); index++)
        {
            char character = code.charAt(index);
            if (character < '0' || character > '9')
            {
                return false;
            }
        }
        return true;
    }

    static String generateCode(byte[] secret, long step)
    {
        try
        {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(secret, "HmacSHA1"));
            byte[] hash = mac.doFinal(ByteBuffer.allocate(Long.BYTES).putLong(step).array());
            int offset = hash[hash.length - 1] & 0x0f;
            int binary = ((hash[offset] & 0x7f) << 24)
                    | ((hash[offset + 1] & 0xff) << 16)
                    | ((hash[offset + 2] & 0xff) << 8)
                    | (hash[offset + 3] & 0xff);
            return String.format(Locale.ROOT, "%06d", binary % CODE_MODULUS);
        }
        catch (GeneralSecurityException exception)
        {
            throw new IllegalStateException("Unable to generate TOTP code", exception);
        }
    }
}
