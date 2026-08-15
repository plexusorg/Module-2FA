package dev.plex.crypto;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public final class SecretEncryption
{
    private static final int KEY_LENGTH = 32;
    private static final int IV_LENGTH = 12;
    private static final int GCM_TAG_BITS = 128;
    private static final String KEY_ENVIRONMENT_VARIABLE = "PLEX_2FA_MASTER_KEY";

    private final SecretKey key;
    private final SecureRandom secureRandom = new SecureRandom();

    private SecretEncryption(byte[] key)
    {
        this.key = new SecretKeySpec(key, "AES");
    }

    public static SecretEncryption load(Path dataFolder) throws IOException
    {
        String configuredKey = System.getenv(KEY_ENVIRONMENT_VARIABLE);
        if (configuredKey != null && !configuredKey.isBlank())
        {
            return new SecretEncryption(validateKey(Base64.getDecoder().decode(configuredKey.trim())));
        }

        Files.createDirectories(dataFolder);
        Path keyFile = dataFolder.resolve("two-factor.key");
        if (Files.exists(keyFile))
        {
            restrictKeyFile(keyFile);
            return new SecretEncryption(validateKey(Files.readAllBytes(keyFile)));
        }

        synchronized (SecretEncryption.class)
        {
            Path lockFile = dataFolder.resolve("two-factor.key.lock");
            try (FileChannel lockChannel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 var ignored = lockChannel.lock())
            {
                if (Files.exists(keyFile))
                {
                    restrictKeyFile(keyFile);
                    return new SecretEncryption(validateKey(Files.readAllBytes(keyFile)));
                }

                byte[] generatedKey = new byte[KEY_LENGTH];
                new SecureRandom().nextBytes(generatedKey);
                Path temporaryKey = createTemporaryKeyFile(dataFolder);
                try
                {
                    try (FileChannel keyChannel = FileChannel.open(temporaryKey, StandardOpenOption.WRITE))
                    {
                        ByteBuffer keyBuffer = ByteBuffer.wrap(generatedKey);
                        while (keyBuffer.hasRemaining())
                        {
                            keyChannel.write(keyBuffer);
                        }
                        keyChannel.force(true);
                    }
                    try
                    {
                        Files.move(temporaryKey, keyFile, StandardCopyOption.ATOMIC_MOVE);
                    }
                    catch (AtomicMoveNotSupportedException exception)
                    {
                        throw new IOException("Atomic master-key publication is not supported; configure " + KEY_ENVIRONMENT_VARIABLE, exception);
                    }
                    restrictKeyFile(keyFile);
                    return new SecretEncryption(generatedKey);
                }
                finally
                {
                    Files.deleteIfExists(temporaryKey);
                }
            }
        }
    }

    public EncryptedSecret encrypt(UUID playerUuid, byte[] secret)
    {
        try
        {
            byte[] iv = new byte[IV_LENGTH];
            secureRandom.nextBytes(iv);
            Cipher cipher = cipher(Cipher.ENCRYPT_MODE, iv, playerUuid);
            return new EncryptedSecret(
                    Base64.getEncoder().encodeToString(cipher.doFinal(secret)),
                    Base64.getEncoder().encodeToString(iv));
        }
        catch (GeneralSecurityException exception)
        {
            throw new IllegalStateException("Unable to encrypt two-factor secret", exception);
        }
    }

    public byte[] decrypt(UUID playerUuid, String encryptedSecret, String initializationVector)
    {
        try
        {
            byte[] iv = Base64.getDecoder().decode(initializationVector);
            Cipher cipher = cipher(Cipher.DECRYPT_MODE, iv, playerUuid);
            return cipher.doFinal(Base64.getDecoder().decode(encryptedSecret));
        }
        catch (GeneralSecurityException | IllegalArgumentException exception)
        {
            throw new IllegalStateException("Unable to decrypt two-factor secret", exception);
        }
    }

    private Cipher cipher(int mode, byte[] iv, UUID playerUuid) throws GeneralSecurityException
    {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
        cipher.updateAAD(playerUuid.toString().getBytes(StandardCharsets.UTF_8));
        return cipher;
    }

    private static byte[] validateKey(byte[] key)
    {
        if (key.length != KEY_LENGTH)
        {
            throw new IllegalStateException(KEY_ENVIRONMENT_VARIABLE + " must decode to exactly 32 bytes");
        }
        return key;
    }

    private static void restrictKeyFile(Path keyFile) throws IOException
    {
        if (Files.getFileAttributeView(keyFile, PosixFileAttributeView.class) == null)
        {
            return;
        }
        Files.setPosixFilePermissions(keyFile, keyFilePermissions());
    }

    private static Set<PosixFilePermission> keyFilePermissions()
    {
        return EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
    }

    private static Path createTemporaryKeyFile(Path dataFolder) throws IOException
    {
        if (Files.getFileAttributeView(dataFolder, PosixFileAttributeView.class) == null)
        {
            return Files.createTempFile(dataFolder, "two-factor-", ".key.tmp");
        }
        return Files.createTempFile(
                dataFolder,
                "two-factor-",
                ".key.tmp",
                PosixFilePermissions.asFileAttribute(keyFilePermissions()));
    }
}
