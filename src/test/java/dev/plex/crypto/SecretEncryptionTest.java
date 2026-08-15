package dev.plex.crypto;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SecretEncryptionTest
{
    @Test
    void concurrentInitializationPublishesOneCompleteKey(@TempDir Path dataFolder) throws Exception
    {
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2))
        {
            CompletableFuture<SecretEncryption> first = CompletableFuture.supplyAsync(() -> load(dataFolder, start), executor);
            CompletableFuture<SecretEncryption> second = CompletableFuture.supplyAsync(() -> load(dataFolder, start), executor);
            start.countDown();

            SecretEncryption firstEncryption = first.join();
            SecretEncryption secondEncryption = second.join();
            UUID playerUuid = UUID.randomUUID();
            byte[] secret = new byte[]{9, 8, 7, 6};
            EncryptedSecret encrypted = firstEncryption.encrypt(playerUuid, secret);

            assertArrayEquals(secret, secondEncryption.decrypt(
                    playerUuid,
                    encrypted.ciphertext(),
                    encrypted.initializationVector()));
            assertEquals(32, Files.size(dataFolder.resolve("two-factor.key")));
        }
    }

    private SecretEncryption load(Path dataFolder, CountDownLatch start)
    {
        try
        {
            start.await();
            return SecretEncryption.load(dataFolder);
        }
        catch (Exception exception)
        {
            throw new IllegalStateException(exception);
        }
    }
}
