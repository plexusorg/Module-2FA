package dev.plex.crypto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class TotpServiceTest
{
    @Test
    void generatesKnownRfc6238Code()
    {
        byte[] secret = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);

        assertEquals("287082", TotpService.generateCode(secret, 1));
    }

    @Test
    void encodesBase32WithoutPadding()
    {
        assertEquals("JBSWY3DPEBLW64TMMQ", Base32.encode("Hello World".getBytes(StandardCharsets.US_ASCII)));
    }

    @Test
    void rejectsFabricatedDialogInputAndConsumedSteps()
    {
        byte[] secret = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);
        TotpService service = new TotpService(
                new SecureRandom(),
                Clock.fixed(Instant.ofEpochSecond(30), ZoneOffset.UTC));

        assertTrue(service.findMatchingStep(secret, " 287082 ").isEmpty());
        assertTrue(service.findMatchingStep(secret, "287082", 1).isEmpty());
        assertEquals(1, service.findMatchingStep(secret, "287082").orElseThrow());
    }
}
