package com.limelight.porta;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static com.limelight.porta.SessionFailure.Cause.*;

public class SessionFailureTest {
    @Test public void suspectPortsAloneDoNotProveTheyAreBlocked() {
        assertEquals(NO_RESPONSE, SessionFailure.cause(0, 3, -1));
        assertEquals(NO_RESPONSE, SessionFailure.cause(0, 3, 0));
        assertEquals(NO_RESPONSE, SessionFailure.cause(-1, 0, -1));
    }
    @Test public void confirmedBlockedPortsHaveAnActionableMessage() {
        assertEquals(PORTS_BLOCKED, SessionFailure.cause(-1, 3, 1));
        assertEquals(NO_RESPONSE, SessionFailure.cause(-1, 0, 1));
    }
    @Test public void authenticationRequiresPairingAgainRatherThanRetry() {
        assertEquals(PAIRING_REVOKED, SessionFailure.cause(401, 3, 1));
        assertEquals(PAIRING_REVOKED, SessionFailure.cause(403, 0, -1));
    }
    @Test public void serviceUnavailableDiffersFromAnUnreachableHost() {
        assertEquals(SERVICE_UNAVAILABLE, SessionFailure.cause(503, 0, -1));
        assertEquals(NO_RESPONSE, SessionFailure.cause(500, 0, -1));
        assertEquals(PORTS_BLOCKED, SessionFailure.cause(503, 3, 1));
    }
}
