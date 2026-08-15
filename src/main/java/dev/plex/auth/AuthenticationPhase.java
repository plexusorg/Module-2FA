package dev.plex.auth;

enum AuthenticationPhase
{
    LOADING,
    AWAITING_ENROLLMENT_APPROVAL,
    ENROLLING,
    VERIFYING,
    PROCESSING,
    RESETTING,
    TERMINATING
}
