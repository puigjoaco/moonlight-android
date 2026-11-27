package com.limelight.porta;

/** UI categories only. Port flags identify suspect ports, not proof of a firewall. */
public final class SessionFailure {
    public enum Cause { NO_RESPONSE, SERVICE_UNAVAILABLE, PAIRING_REVOKED, PORTS_BLOCKED }
    private SessionFailure() {}

    public static Cause cause(int errorCode, int portFlags, int portTestResult) {
        // Authentication is actionable even if an unrelated connectivity probe failed.
        if (errorCode == 401 || errorCode == 403) return Cause.PAIRING_REVOKED;
        if (portFlags != 0 && portTestResult != -1 && portTestResult != 0)
            return Cause.PORTS_BLOCKED;
        if (errorCode == 503) return Cause.SERVICE_UNAVAILABLE;
        return Cause.NO_RESPONSE;
    }
}
