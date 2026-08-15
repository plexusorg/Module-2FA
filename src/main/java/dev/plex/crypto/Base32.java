package dev.plex.crypto;

public final class Base32
{
    private static final char[] ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".toCharArray();

    private Base32()
    {
    }

    public static String encode(byte[] value)
    {
        StringBuilder result = new StringBuilder((value.length * 8 + 4) / 5);
        int buffer = 0;
        int bitsLeft = 0;
        for (byte current : value)
        {
            buffer = (buffer << 8) | (current & 0xff);
            bitsLeft += 8;
            while (bitsLeft >= 5)
            {
                result.append(ALPHABET[(buffer >> (bitsLeft - 5)) & 0x1f]);
                bitsLeft -= 5;
            }
        }
        if (bitsLeft > 0)
        {
            result.append(ALPHABET[(buffer << (5 - bitsLeft)) & 0x1f]);
        }
        return result.toString();
    }
}
