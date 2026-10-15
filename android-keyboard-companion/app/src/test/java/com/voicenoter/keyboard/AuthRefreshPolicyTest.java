package com.voicenoter.keyboard;

import org.junit.Test;
import static org.junit.Assert.*;

public class AuthRefreshPolicyTest {
    @Test public void unknownClientErrorsDoNotDiscardSession() {
        assertFalse(AuthRefreshPolicy.requiresSignIn(400, "bad_json"));
        assertFalse(AuthRefreshPolicy.requiresSignIn(401, "unknown"));
        assertFalse(AuthRefreshPolicy.requiresSignIn(403, "insufficient_aal"));
    }

    @Test public void temporaryFailuresDoNotDiscardSessionEvenWithMisleadingBody() {
        for (int status : new int[]{0, 200, 409, 429, 500, 503}) {
            assertFalse(AuthRefreshPolicy.requiresSignIn(status, "refresh_token_not_found"));
        }
    }

    @Test public void invalidOrRevokedSessionRequiresNewLogin() {
        for (String code : new String[]{"refresh_token_not_found", "refresh_token_already_used",
                "session_not_found", "session_expired", "user_not_found", "user_banned"}) {
            assertTrue(AuthRefreshPolicy.requiresSignIn(400, code));
        }
    }

    @Test public void failedEarlyRefreshCanStillUseUnexpiredAccess() {
        assertEquals("access", AuthRefreshPolicy.unexpiredAccess("access", 1100, 1000));
        assertNull(AuthRefreshPolicy.unexpiredAccess("access", 1000, 1000));
        assertNull(AuthRefreshPolicy.unexpiredAccess("access", 900, 1000));
        assertNull(AuthRefreshPolicy.unexpiredAccess("", 1100, 1000));
    }

    @Test public void diagnosticFieldsCannotContainTokenBodyOrIdentifier() {
        assertEquals("session_expired", AuthRefreshPolicy.safeCode("session_expired"));
        assertEquals("unknown", AuthRefreshPolicy.safeCode("Bearer secret.token.value"));
        assertEquals("unknown", AuthRefreshPolicy.safeCode("user@example.com"));
        assertEquals("unknown", AuthRefreshPolicy.safeCode(null));
    }
}
