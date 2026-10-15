package com.voicenoter.keyboard;

/** Decisions depend on Auth's error code, never on an HTTP status alone. */
final class AuthRefreshPolicy {
    private AuthRefreshPolicy() {}

    static boolean requiresSignIn(int status, String code) {
        if (status != 400 && status != 401 && status != 403) return false;
        return "refresh_token_not_found".equals(code)
            || "refresh_token_already_used".equals(code)
            || "session_not_found".equals(code)
            || "session_expired".equals(code)
            || "user_not_found".equals(code)
            || "user_banned".equals(code);
    }

    static String unexpiredAccess(String access, long expiresAt, long now) {
        return access != null && !access.isEmpty() && expiresAt > now ? access : null;
    }

    static String safeCode(String code) {
        return code != null && code.matches("[a-z0-9_]{1,64}") ? code : "unknown";
    }
}
