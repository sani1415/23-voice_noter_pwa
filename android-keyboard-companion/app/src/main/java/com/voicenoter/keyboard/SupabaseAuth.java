package com.voicenoter.keyboard;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Voice Notyper account for the keyboard. The server's /api endpoints only serve
 * signed-in, active users, so the keyboard keeps its own Supabase session (same
 * account as the notes app) and sends the access token with every request.
 * Network calls block: call off the main thread.
 */
final class SupabaseAuth {
    // Public project URL and anon key — the same values the PWA ships in app.js.
    static final String SUPABASE_URL = "https://iqjajofqaimnqvrxgdzc.supabase.co";
    static final String SUPABASE_ANON_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6ImlxamFqb2ZxYWltbnF2cnhnZHpjIiwicm9sZSI6ImFub24iLCJpYXQiOjE3NzEyMDM0NzYsImV4cCI6MjA4Njc3OTQ3Nn0.epEV-GGXUsJe-u4Aumx284qGPrCFZcuXFGN9qmtwwbM";

    private static final String PREFS = "voice_keyboard_auth";
    private static final String KEY_ACCESS = "access_token";
    private static final String KEY_REFRESH = "refresh_token";
    private static final String KEY_EXPIRES_AT = "expires_at"; // epoch seconds
    private static final String KEY_EMAIL = "email";
    /** Refresh a little before expiry so a token never dies mid-request. */
    private static final long EXPIRY_MARGIN_S = 120;
    /** Opening the keyboard renews a token that has less than this left. */
    private static final long WARM_MARGIN_S = 15 * 60;
    private static final int REFRESH_ATTEMPTS = 3;
    static final long[] RETRY_DELAYS_MS = {700, 1800};

    private SupabaseAuth() {}

    /** Thrown with a message that can be shown to the user as-is. */
    static final class AuthException extends Exception {
        AuthException(String message) { super(message); }
    }

    static boolean isSignedIn(Context context) {
        return !prefs(context).getString(KEY_REFRESH, "").isEmpty();
    }

    static String signedInEmail(Context context) {
        return prefs(context).getString(KEY_EMAIL, "");
    }

    static synchronized void signIn(Context context, String email, String password) throws AuthException {
        JSONObject body = new JSONObject();
        try {
            body.put("email", email);
            body.put("password", password);
        } catch (Exception e) {
            throw new AuthException("লগইন করা যায়নি");
        }
        Response res = post("/auth/v1/token?grant_type=password", body, null);
        if (res == null) throw new AuthException("নেট সংযোগ নেই, আবার চেষ্টা করুন");
        if (res.code == 400 || res.code == 401) throw new AuthException("ইমেইল বা পাসওয়ার্ড ভুল");
        if (res.code < 200 || res.code >= 300) throw new AuthException("লগইন করা যায়নি (" + res.code + ")");

        JSONObject session = res.json();
        String access = session.optString("access_token", "");
        if (access.isEmpty() || session.optString("refresh_token", "").isEmpty()) {
            VoiceDiagnostics.record(context, "sign_in", "invalid_response", res.code);
            throw new AuthException("লগইন করা যায়নি");
        }
        JSONObject user = session.optJSONObject("user");
        String userId = user != null ? user.optString("id", "") : "";
        if (!isActiveProfile(access, userId)) {
            throw new AuthException("এই অ্যাকাউন্টে Voice Notyper access নেই");
        }
        save(context, session, email);
    }

    static synchronized void signOut(Context context) {
        prefs(context).edit().clear().apply();
    }

    /**
     * A usable access token, refreshing it when it is about to expire; null when
     * not signed in or the refresh failed. Only an explicit invalid-session code
     * clears credentials. Temporary/unknown errors keep the session for retry.
     */
    static String validAccessToken(Context context) {
        return validAccessToken(context, EXPIRY_MARGIN_S);
    }

    /**
     * Refreshes ahead of time when the token has less than {@code WARM_MARGIN_S} left.
     * Called when the keyboard opens, so pressing the mic rarely needs the network
     * for login at all. Blocking: call off the main thread.
     */
    static void warmUp(Context context) {
        validAccessToken(context, WARM_MARGIN_S);
    }

    private static synchronized String validAccessToken(Context context, long marginS) {
        SharedPreferences p = prefs(context);
        String refresh = p.getString(KEY_REFRESH, "");
        if (refresh.isEmpty()) return null;
        String access = p.getString(KEY_ACCESS, "");
        long now = System.currentTimeMillis() / 1000;
        if (!access.isEmpty() && p.getLong(KEY_EXPIRES_AT, 0) - marginS > now) {
            return access;
        }

        JSONObject body = new JSONObject();
        try {
            body.put("refresh_token", refresh);
        } catch (Exception e) {
            return null;
        }
        // Mobile data often drops for a moment (waking up, switching towers): retry
        // quietly before treating the server as unreachable.
        Response res = null;
        for (int attempt = 0; attempt < REFRESH_ATTEMPTS && res == null; attempt++) {
            if (attempt > 0) sleepQuietly(RETRY_DELAYS_MS[attempt - 1]);
            res = post("/auth/v1/token?grant_type=refresh_token", body, null);
        }
        if (res == null) {
            VoiceDiagnostics.record(context, "session_refresh", "network_unavailable", 0);
            return AuthRefreshPolicy.unexpiredAccess(access, p.getLong(KEY_EXPIRES_AT, 0), now);
        }
        if (res.code < 200 || res.code >= 300) {
            JSONObject error = res.json();
            String code = error.optString("error_code", error.optString("code", "unknown"));
            VoiceDiagnostics.record(context, "session_refresh", code, res.code);
            if (!AuthRefreshPolicy.requiresSignIn(res.code, code)) {
                return AuthRefreshPolicy.unexpiredAccess(access, p.getLong(KEY_EXPIRES_AT, 0), now);
            }
            signOut(context);
            SonioxKeyCache.clear(context);
            return null;
        }
        JSONObject session = res.json();
        if (session.optString("access_token", "").isEmpty()
            || session.optString("refresh_token", "").isEmpty()) {
            VoiceDiagnostics.record(context, "session_refresh", "invalid_response", res.code);
            return AuthRefreshPolicy.unexpiredAccess(access, p.getLong(KEY_EXPIRES_AT, 0), now);
        }
        save(context, session, p.getString(KEY_EMAIL, ""));
        return session.optString("access_token", "");
    }

    static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static boolean isActiveProfile(String accessToken, String userId) throws AuthException {
        if (userId.isEmpty()) return false;
        String path;
        try {
            path = "/rest/v1/vn_profiles?select=is_active&user_id=eq."
                + URLEncoder.encode(userId, "UTF-8");
        } catch (Exception e) {
            return false;
        }
        Response res = request("GET", path, null, accessToken);
        if (res == null) throw new AuthException("নেট সংযোগ নেই, আবার চেষ্টা করুন");
        if (res.code < 200 || res.code >= 300) return false;
        try {
            JSONArray rows = new JSONArray(res.body);
            return rows.length() > 0 && rows.getJSONObject(0).optBoolean("is_active", false);
        } catch (Exception e) {
            return false;
        }
    }

    private static void save(Context context, JSONObject session, String email) {
        long now = System.currentTimeMillis() / 1000;
        long expiresAt = session.optLong("expires_at", 0);
        if (expiresAt <= 0) expiresAt = now + session.optLong("expires_in", 3600);
        prefs(context).edit()
            .putString(KEY_ACCESS, session.optString("access_token", ""))
            .putString(KEY_REFRESH, session.optString("refresh_token", ""))
            .putLong(KEY_EXPIRES_AT, expiresAt)
            .putString(KEY_EMAIL, email)
            // Persist the rotated refresh token before another request/process restart.
            .commit();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static final class Response {
        final int code;
        final String body;

        Response(int code, String body) {
            this.code = code;
            this.body = body;
        }

        JSONObject json() {
            try {
                return new JSONObject(body);
            } catch (Exception e) {
                return new JSONObject();
            }
        }
    }

    private static Response post(String path, JSONObject body, String bearer) {
        return request("POST", path, body, bearer);
    }

    /** Null when the server could not be reached at all. */
    private static Response request(String method, String path, JSONObject body, String bearer) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(SUPABASE_URL + path).openConnection();
            connection.setRequestMethod(method);
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(20000);
            connection.setRequestProperty("apikey", SUPABASE_ANON_KEY);
            connection.setRequestProperty("Authorization",
                "Bearer " + (bearer != null ? bearer : SUPABASE_ANON_KEY));
            if (body != null) {
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                connection.setDoOutput(true);
                try (OutputStream out = connection.getOutputStream()) {
                    out.write(body.toString().getBytes(StandardCharsets.UTF_8));
                }
            }
            int code = connection.getResponseCode();
            InputStream stream = code >= 200 && code < 300
                ? connection.getInputStream() : connection.getErrorStream();
            StringBuilder text = new StringBuilder();
            if (stream != null) {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) text.append(line);
                }
            }
            return new Response(code, text.toString());
        } catch (Exception e) {
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }
}
