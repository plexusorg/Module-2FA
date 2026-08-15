package dev.plex.crypto;

public record EncryptedSecret(String ciphertext, String initializationVector)
{
}
