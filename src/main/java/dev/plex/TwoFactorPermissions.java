package dev.plex;

import java.util.List;

public final class TwoFactorPermissions
{
    public static final String REQUIRED = "plex.2fa.required";
    public static final String REQUIRED_CRACKED = "plex.2fa.required.cracked";
    public static final String ADMIN = "plex.2fa.admin";
    public static final List<String> ALL = List.of(REQUIRED, REQUIRED_CRACKED, ADMIN);

    private TwoFactorPermissions()
    {
    }
}
