package com.voicenoter.keyboard;

import android.content.Context;
import android.content.SharedPreferences;
import java.text.DateFormat;
import java.util.Date;

/** Only stage, error code, HTTP status and time; no tokens, bodies or audio. */
final class VoiceDiagnostics {
    private static final String PREFS = "voice_keyboard_diagnostics";
    private VoiceDiagnostics() {}

    static void record(Context context, String stage, String code, int status) {
        prefs(context).edit()
            .putString("stage", AuthRefreshPolicy.safeCode(stage))
            .putString("code", AuthRefreshPolicy.safeCode(code))
            .putInt("http_status", status)
            .putLong("at", System.currentTimeMillis()).apply();
    }

    static String summary(Context context) {
        SharedPreferences p = prefs(context);
        long at = p.getLong("at", 0L);
        if (at == 0L) return "";
        String code = p.getString("code", "unknown");
        int status = p.getInt("http_status", 0);
        String reason;
        if (AuthRefreshPolicy.requiresSignIn(status, code)) {
            reason = "সার্ভার আগের লগইন বাতিল করেছে; আবার লগইন প্রয়োজন হয়েছিল।";
        } else if ("auth_required".equals(code)) {
            reason = "লগইন করা ছিল না।";
        } else if ("network_unavailable".equals(code)) {
            reason = "সার্ভারে পৌঁছানো যায়নি; লগইন রাখা হয়েছে।";
        } else {
            reason = "ভয়েস সংযোগ বা লগইন যাচাই ব্যর্থ হয়েছিল।";
        }
        return "শেষ সমস্যা: " + reason + "\n"
            + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(new Date(at))
            + " · " + p.getString("stage", "unknown") + " / " + code
            + (status > 0 ? " · HTTP " + status : "");
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
