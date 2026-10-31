package com.voicenoter.keyboard;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

public class MainActivity extends Activity {
    static final String PREFS = "voice_noter_keyboard";
    static final String KEY_ENDPOINT = "endpoint";
    static final String KEY_VOICE_ONLY = "voice_only_mode";
    static final String KEY_LAYOUT_LANG = "layout_lang";
    static final String KEY_VOICE_INPUT_MODE = "voice_input_mode";
    static final String KEY_THEME = "kbd_theme";
    static final String KEY_ENABLED_LANGS = "enabled_langs";
    static final String KEY_PRESETS = "preset_messages";
    static final String KEY_HAPTIC = "haptic_feedback";
    static final String KEY_HOLD_TO_TALK = "hold_to_talk";
    static final String KEY_PHONETIC_BN = "phonetic_bn";
    static final String KEY_KEY_SIZE = "key_size";
    /** One-shot: v0.4.1 turns phonetic off so Bangla shows native letters by default. */
    static final String KEY_COMFORT_FIX_0401 = "comfort_fix_0_4_1";

    static final String DEFAULT_PRESETS = "[]";

    static final String LANG_BN = "bn";
    static final String LANG_EN = "en";
    static final String LANG_AR = "ar";

    static final String MODE_RECORD = "record";
    static final String MODE_LIVE = "live";

    static final String THEME_WARM = "warm";
    static final String THEME_LIGHT = "light";
    static final String THEME_DARK = "dark";
    static final String THEME_COLORFUL = "colorful";

    static final String SIZE_SMALL = "small";
    static final String SIZE_MEDIUM = "medium";
    static final String SIZE_LARGE = "large";

    static final String DEFAULT_ENDPOINT = "https://notes.idarah786.com";
    static final String DEFAULT_ENABLED_LANGS = "bn,en,ar";
    static final String DEFAULT_THEME = THEME_WARM;
    static final String DEFAULT_KEY_SIZE = SIZE_MEDIUM;

    /** Opened from the IME settings gear — Back should leave this task, not open the PWA. */
    static final String EXTRA_FROM_IME = "from_ime";
    static final String EXTRA_SHOW_ACCOUNT = "show_account";

    private static final int REQ_AUDIO = 1001;

    /**
     * Theme previews: tray, key and accent colours, the same values the keyboard's
     * applyTheme() uses, so the miniature looks like what you get.
     */
    private static final String[] THEMES = {THEME_WARM, THEME_LIGHT, THEME_DARK, THEME_COLORFUL};
    private static final String[] THEME_NAMES = {"উষ্ণ", "হালকা", "গাঢ়", "রঙিন"};
    private static final int[][] THEME_COLORS = {
        {0xffE9E8E1, 0xffFFFFFF, 0xff3F6B4A},
        {0xffECEFF1, 0xffFFFFFF, 0xff1A73E8},
        {0xff1C1E1B, 0xff2E312C, 0xff9DC3A3},
        {0xff15203A, 0xff243056, 0xffFFB300},
    };

    private SharedPreferences prefs;
    private EditText endpointInput;
    private boolean openedFromIme;

    private TextView langBn, langEn, langAr;
    private TextView modeRecord, modeLive;
    private TextView sizeSmall, sizeMedium, sizeLarge;
    private TextView chkBn, chkEn, chkAr;
    private TextView modeHelp;
    private View advancedBody;
    private ImageView advancedChevron;
    private final View[] themeSwatches = new View[THEMES.length];
    private final TextView[] themeLabels = new TextView[THEMES.length];

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTheme(R.style.AppTheme);
        setContentView(R.layout.activity_main);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        applyComfortFix0401(prefs);
        openedFromIme = getIntent() != null && getIntent().getBooleanExtra(EXTRA_FROM_IME, false);

        tint(R.id.header_icon, R.color.primary);
        tint(R.id.setup_ready_icon, R.color.success);
        tint(R.id.advanced_chevron, R.color.text_muted);
        bindSetup();
        bindAccount();
        bindLanguage();
        bindVoiceMode();
        bindVoiceOnly();
        bindComfortToggles();
        bindKeySize();
        bindEnabledLanguages();
        bindTheme();
        bindAdvanced();
        bindVersion();
        showAccountIfRequested();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        openedFromIme = intent != null && intent.getBooleanExtra(EXTRA_FROM_IME, false);
        refreshAccountCard();
        showAccountIfRequested();
    }

    @Override
    public void onBackPressed() {
        // From the keyboard gear: remove this settings task so Back returns to the typing app.
        if (openedFromIme && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            finishAndRemoveTask();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshSetup();
        refreshAccountCard();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        // The keyboard picker is a dialog over this screen, so no onResume follows it.
        if (hasFocus) refreshSetup();
    }

    // ---------- Setup ----------

    private View stepMic, stepEnable, stepDefault;

    private void bindSetup() {
        stepMic = findViewById(R.id.step_mic);
        stepEnable = findViewById(R.id.step_enable);
        stepDefault = findViewById(R.id.step_default);
        stepMic.findViewById(R.id.step_action).setOnClickListener(v -> requestMicPermission());
        stepEnable.findViewById(R.id.step_action).setOnClickListener(v ->
            startActivity(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)));
        stepDefault.findViewById(R.id.step_action).setOnClickListener(v -> {
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.showInputMethodPicker();
        });
    }

    /** Checks the three things the keyboard needs; folds to one line when all are done. */
    private void refreshSetup() {
        boolean mic = Build.VERSION.SDK_INT < Build.VERSION_CODES.M
            || checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        boolean enabled = isKeyboardEnabled();
        boolean chosen = enabled && isKeyboardDefault();
        setStep(stepMic, mic, R.drawable.lc_mic, "মাইকের অনুমতি",
            mic ? "দেওয়া আছে" : "ভয়েস দিয়ে লিখতে লাগবে", "অনুমতি দিন");
        setStep(stepEnable, enabled, R.drawable.lc_keyboard, "কিবোর্ড চালু করা",
            enabled ? "চালু আছে" : "ফোনের কিবোর্ড তালিকায় চালু করুন", "চালু করুন");
        setStep(stepDefault, chosen, R.drawable.lc_check, "মূল কিবোর্ড হিসেবে বাছাই",
            chosen ? "এটাই ব্যবহার হচ্ছে" : "এখন অন্য কিবোর্ড ব্যবহার হচ্ছে", "বেছে নিন");
        boolean ready = mic && enabled && chosen;
        findViewById(R.id.setup_ready).setVisibility(ready ? View.VISIBLE : View.GONE);
        findViewById(R.id.setup_steps).setVisibility(ready ? View.GONE : View.VISIBLE);
    }

    private void setStep(View step, boolean done, int icon, String title, String sub, String action) {
        ((TextView) step.findViewById(R.id.step_title)).setText(title);
        ((TextView) step.findViewById(R.id.step_sub)).setText(sub);
        step.findViewById(R.id.step_dot).setBackgroundResource(done ? R.drawable.dot_done : R.drawable.dot_todo);
        ImageView iconView = step.findViewById(R.id.step_icon);
        iconView.setImageResource(done ? R.drawable.lc_check : icon);
        iconView.setColorFilter(getColor(done ? R.color.primary_dark : R.color.pending_text), PorterDuff.Mode.SRC_IN);
        TextView button = step.findViewById(R.id.step_action);
        button.setText(action);
        button.setVisibility(done ? View.GONE : View.VISIBLE);
    }

    private boolean isKeyboardEnabled() {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm == null) return false;
        for (InputMethodInfo info : imm.getEnabledInputMethodList()) {
            if (getPackageName().equals(info.getPackageName())) return true;
        }
        return false;
    }

    private boolean isKeyboardDefault() {
        String current = Settings.Secure.getString(getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
        return current != null && current.startsWith(getPackageName() + "/");
    }

    private void requestMicPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
            && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_AUDIO);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_AUDIO) {
            boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            refreshSetup();
            if (!granted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName())));
            }
        }
    }

    // ---------- Account ----------

    private void bindAccount() {
        EditText email = findViewById(R.id.account_email);
        EditText password = findViewById(R.id.account_password);
        Button signIn = findViewById(R.id.btn_sign_in);
        findViewById(R.id.btn_sign_out).setOnClickListener(v -> {
            SupabaseAuth.signOut(this);
            SonioxKeyCache.clear(this);
            refreshAccountCard();
        });
        signIn.setOnClickListener(v -> {
            String e = email.getText().toString().trim();
            String p = password.getText().toString();
            if (e.isEmpty() || p.isEmpty()) {
                Toast.makeText(this, "ইমেইল আর পাসওয়ার্ড দিন", Toast.LENGTH_SHORT).show();
                return;
            }
            signIn.setEnabled(false);
            signIn.setText("লগইন হচ্ছে…");
            new Thread(() -> {
                String error = null;
                try {
                    SupabaseAuth.signIn(this, e, p);
                } catch (SupabaseAuth.AuthException ex) {
                    error = ex.getMessage();
                }
                final String message = error;
                runOnUiThread(() -> {
                    signIn.setEnabled(true);
                    signIn.setText("লগইন");
                    if (message != null) {
                        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
                        return;
                    }
                    password.setText("");
                    refreshAccountCard();
                    Toast.makeText(this, "লগইন হয়েছে", Toast.LENGTH_SHORT).show();
                });
            }, "keyboard-sign-in").start();
        });
        TextView diagnostics = findViewById(R.id.account_diagnostics);
        TextView action = findViewById(R.id.account_diagnostics_action);
        action.setOnClickListener(v -> {
            boolean show = diagnostics.getVisibility() != View.VISIBLE;
            diagnostics.setVisibility(show ? View.VISIBLE : View.GONE);
            action.setText(show ? "সমস্যার তথ্য লুকান" : "সর্বশেষ সমস্যার তথ্য দেখুন");
        });
        refreshAccountCard();
    }

    private void refreshAccountCard() {
        boolean signedIn = SupabaseAuth.isSignedIn(this);
        String email = SupabaseAuth.signedInEmail(this);
        findViewById(R.id.account_signed_in).setVisibility(signedIn ? View.VISIBLE : View.GONE);
        findViewById(R.id.account_form).setVisibility(signedIn ? View.GONE : View.VISIBLE);
        ((TextView) findViewById(R.id.account_email_text)).setText(email);
        ((TextView) findViewById(R.id.account_avatar)).setText(
            email.isEmpty() ? "" : email.substring(0, 1).toUpperCase(java.util.Locale.ROOT));
        String summary = VoiceDiagnostics.summary(this);
        ((TextView) findViewById(R.id.account_diagnostics)).setText(summary);
        findViewById(R.id.account_diagnostics_box).setVisibility(summary.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void showAccountIfRequested() {
        if (getIntent() == null || !getIntent().getBooleanExtra(EXTRA_SHOW_ACCOUNT, false)) return;
        View card = findViewById(R.id.account_card);
        card.post(() -> {
            card.getParent().requestChildFocus(card, card);
            if (!SupabaseAuth.isSignedIn(this)) findViewById(R.id.account_email).requestFocus();
        });
        getIntent().removeExtra(EXTRA_SHOW_ACCOUNT);
    }

    // ---------- Default language ----------

    private void bindLanguage() {
        langBn = findViewById(R.id.lang_bn);
        langEn = findViewById(R.id.lang_en);
        langAr = findViewById(R.id.lang_ar);
        langBn.setOnClickListener(v -> selectLanguage(LANG_BN));
        langEn.setOnClickListener(v -> selectLanguage(LANG_EN));
        langAr.setOnClickListener(v -> selectLanguage(LANG_AR));
        styleLanguageChips();
    }

    private void selectLanguage(String lang) {
        prefs.edit().putString(KEY_LAYOUT_LANG, lang).apply();
        // A default language must also be shown on the keyboard.
        Set<String> enabled = enabledLangs();
        if (!enabled.contains(lang)) {
            enabled.add(lang);
            saveEnabledLangs(enabled);
            syncEnabledChips();
        }
        styleLanguageChips();
    }

    private void styleLanguageChips() {
        String current = prefs.getString(KEY_LAYOUT_LANG, LANG_BN);
        setSegment(langBn, LANG_BN.equals(current));
        setSegment(langEn, LANG_EN.equals(current));
        setSegment(langAr, LANG_AR.equals(current));
    }

    // ---------- Voice input mode ----------

    private void bindVoiceMode() {
        modeRecord = findViewById(R.id.mode_record);
        modeLive = findViewById(R.id.mode_live);
        modeHelp = findViewById(R.id.mode_help);
        modeRecord.setOnClickListener(v -> selectMode(MODE_RECORD));
        modeLive.setOnClickListener(v -> selectMode(MODE_LIVE));
        styleModeChips();
    }

    private void selectMode(String mode) {
        prefs.edit().putString(KEY_VOICE_INPUT_MODE, mode).apply();
        styleModeChips();
    }

    private void styleModeChips() {
        String mode = prefs.getString(KEY_VOICE_INPUT_MODE, MODE_RECORD);
        boolean live = MODE_LIVE.equals(mode);
        setSegment(modeRecord, !live);
        setSegment(modeLive, live);
        modeHelp.setText(live
            ? "বলার সাথে সাথে লেখা আসতে থাকে। লম্বা লেখার জন্য ভালো।"
            : "বলা শেষে থামালে পুরো লেখা একবারে আসে। নির্ভুলতা বেশি।");
    }

    // ---------- Switches ----------

    private void bindVoiceOnly() {
        Switch voiceOnly = findViewById(R.id.switch_voice_only);
        voiceOnly.setChecked(prefs.getBoolean(KEY_VOICE_ONLY, false));
        voiceOnly.setOnCheckedChangeListener((b, checked) ->
            prefs.edit().putBoolean(KEY_VOICE_ONLY, checked).apply());
    }

    private void bindComfortToggles() {
        Switch haptic = findViewById(R.id.switch_haptic);
        haptic.setChecked(prefs.getBoolean(KEY_HAPTIC, true));
        haptic.setOnCheckedChangeListener((b, checked) ->
            prefs.edit().putBoolean(KEY_HAPTIC, checked).apply());
    }

    /** Existing 0.4.0 installs had phonetic ON; reset once so native Bangla keys show. */
    static void applyComfortFix0401(SharedPreferences prefs) {
        if (prefs.getBoolean(KEY_COMFORT_FIX_0401, false)) return;
        prefs.edit()
            .putBoolean(KEY_PHONETIC_BN, false)
            .putBoolean(KEY_COMFORT_FIX_0401, true)
            .apply();
    }

    // ---------- Key size ----------

    private void bindKeySize() {
        sizeSmall = findViewById(R.id.size_small);
        sizeMedium = findViewById(R.id.size_medium);
        sizeLarge = findViewById(R.id.size_large);
        sizeSmall.setOnClickListener(v -> selectKeySize(SIZE_SMALL));
        sizeMedium.setOnClickListener(v -> selectKeySize(SIZE_MEDIUM));
        sizeLarge.setOnClickListener(v -> selectKeySize(SIZE_LARGE));
        styleKeySizeChips();
    }

    private void selectKeySize(String size) {
        prefs.edit().putString(KEY_KEY_SIZE, size).apply();
        styleKeySizeChips();
    }

    private void styleKeySizeChips() {
        String size = prefs.getString(KEY_KEY_SIZE, DEFAULT_KEY_SIZE);
        setSegment(sizeSmall, SIZE_SMALL.equals(size));
        setSegment(sizeMedium, SIZE_MEDIUM.equals(size));
        setSegment(sizeLarge, SIZE_LARGE.equals(size));
    }

    // ---------- Enabled languages ----------

    private void bindEnabledLanguages() {
        chkBn = findViewById(R.id.chk_bn);
        chkEn = findViewById(R.id.chk_en);
        chkAr = findViewById(R.id.chk_ar);
        chkBn.setOnClickListener(v -> onEnabledChanged(LANG_BN));
        chkEn.setOnClickListener(v -> onEnabledChanged(LANG_EN));
        chkAr.setOnClickListener(v -> onEnabledChanged(LANG_AR));
        syncEnabledChips();
    }

    private void onEnabledChanged(String lang) {
        Set<String> enabled = enabledLangs();
        if (!enabled.contains(lang)) {
            enabled.add(lang);
        } else {
            if (enabled.size() <= 1) {
                Toast.makeText(this, "অন্তত একটা ভাষা রাখতে হবে", Toast.LENGTH_SHORT).show();
                return;
            }
            enabled.remove(lang);
        }
        saveEnabledLangs(enabled);
        syncEnabledChips();
        // If the default language was just turned off, move it to a still-enabled one.
        String current = prefs.getString(KEY_LAYOUT_LANG, LANG_BN);
        if (!enabled.contains(current)) {
            prefs.edit().putString(KEY_LAYOUT_LANG, enabled.iterator().next()).apply();
            styleLanguageChips();
        }
    }

    private void syncEnabledChips() {
        Set<String> enabled = enabledLangs();
        setToggleChip(chkBn, enabled.contains(LANG_BN));
        setToggleChip(chkEn, enabled.contains(LANG_EN));
        setToggleChip(chkAr, enabled.contains(LANG_AR));
    }

    private Set<String> enabledLangs() {
        String raw = prefs.getString(KEY_ENABLED_LANGS, DEFAULT_ENABLED_LANGS);
        Set<String> set = new LinkedHashSet<>();
        for (String part : raw.split(",")) {
            String s = part.trim();
            if (!s.isEmpty()) set.add(s);
        }
        if (set.isEmpty()) set.add(LANG_BN);
        return set;
    }

    private void saveEnabledLangs(Set<String> enabled) {
        // Persist in a stable order so the keyboard bar order is predictable.
        StringBuilder sb = new StringBuilder();
        for (String lang : Arrays.asList(LANG_BN, LANG_EN, LANG_AR)) {
            if (enabled.contains(lang)) {
                if (sb.length() > 0) sb.append(",");
                sb.append(lang);
            }
        }
        prefs.edit().putString(KEY_ENABLED_LANGS, sb.toString()).apply();
    }

    // ---------- Theme ----------

    private void bindTheme() {
        LinearLayout row = findViewById(R.id.theme_row);
        for (int i = 0; i < THEMES.length; i++) {
            final String theme = THEMES[i];
            LinearLayout cell = new LinearLayout(this);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER_HORIZONTAL);
            cell.setClickable(true);
            cell.setFocusable(true);
            cell.setContentDescription(THEME_NAMES[i] + " থিম");
            cell.setOnClickListener(v -> selectTheme(theme));

            View preview = miniKeyboard(THEME_COLORS[i]);
            FrameLayout ring = new FrameLayout(this);
            ring.setPadding(dp(3), dp(3), dp(3), dp(3));
            ring.addView(preview, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, dp(58)));
            themeSwatches[i] = ring;
            cell.addView(ring, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

            TextView label = new TextView(this);
            label.setText(THEME_NAMES[i]);
            label.setTextSize(14);
            label.setGravity(Gravity.CENTER);
            label.setPadding(0, dp(6), 0, 0);
            themeLabels[i] = label;
            cell.addView(label);

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            if (i > 0) lp.setMarginStart(dp(8));
            row.addView(cell, lp);
        }
        styleThemeChips();
    }

    /** A tiny keyboard in a theme's colours: two key rows and a space bar with the accent key. */
    private View miniKeyboard(int[] colors) {
        LinearLayout tray = new LinearLayout(this);
        tray.setOrientation(LinearLayout.VERTICAL);
        tray.setBackground(round(colors[0], dp(12)));
        tray.setPadding(dp(6), dp(7), dp(6), dp(7));
        for (int r = 0; r < 3; r++) {
            LinearLayout keys = new LinearLayout(this);
            keys.setOrientation(LinearLayout.HORIZONTAL);
            int count = r < 2 ? 5 : 2;
            for (int k = 0; k < count; k++) {
                View key = new View(this);
                boolean accent = r == 2 && k == 1;
                key.setBackground(round(accent ? colors[2] : colors[1], dp(2)));
                LinearLayout.LayoutParams kp = new LinearLayout.LayoutParams(0, dp(10),
                    r == 2 ? (k == 0 ? 3.2f : 1f) : 1f);
                if (k > 0) kp.setMarginStart(dp(3));
                keys.addView(key, kp);
            }
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            if (r > 0) rp.topMargin = dp(4);
            tray.addView(keys, rp);
        }
        return tray;
    }

    private void selectTheme(String theme) {
        prefs.edit().putString(KEY_THEME, theme).apply();
        styleThemeChips();
    }

    private void styleThemeChips() {
        String theme = prefs.getString(KEY_THEME, DEFAULT_THEME);
        for (int i = 0; i < THEMES.length; i++) {
            boolean selected = THEMES[i].equals(theme);
            GradientDrawable ring = round(0x00000000, dp(15));
            if (selected) ring.setStroke(dp(2), getColor(R.color.primary));
            themeSwatches[i].setBackground(ring);
            themeLabels[i].setTextColor(getColor(selected ? R.color.primary_dark : R.color.text_muted));
            themeLabels[i].setTypeface(null, selected ? Typeface.BOLD : Typeface.NORMAL);
        }
    }

    // ---------- Advanced (endpoint) ----------

    private void bindAdvanced() {
        endpointInput = findViewById(R.id.endpoint_input);
        endpointInput.setHint(DEFAULT_ENDPOINT);
        endpointInput.setText(prefs.getString(KEY_ENDPOINT, DEFAULT_ENDPOINT));

        advancedBody = findViewById(R.id.advanced_body);
        advancedChevron = findViewById(R.id.advanced_chevron);
        findViewById(R.id.advanced_header).setOnClickListener(v -> toggleAdvanced());

        findViewById(R.id.btn_save_endpoint).setOnClickListener(v -> saveEndpoint());
        findViewById(R.id.btn_reset_endpoint).setOnClickListener(v -> {
            endpointInput.setText(DEFAULT_ENDPOINT);
            prefs.edit().putString(KEY_ENDPOINT, DEFAULT_ENDPOINT).apply();
            Toast.makeText(this, "আগের ঠিকানায় ফেরানো হয়েছে", Toast.LENGTH_SHORT).show();
        });
    }

    private void toggleAdvanced() {
        boolean show = advancedBody.getVisibility() != View.VISIBLE;
        advancedBody.setVisibility(show ? View.VISIBLE : View.GONE);
        advancedChevron.setImageResource(show ? R.drawable.lc_chevron_up : R.drawable.lc_chevron_down);
    }

    private void saveEndpoint() {
        String endpoint = endpointInput.getText().toString().trim();
        if (endpoint.endsWith("/")) endpoint = endpoint.substring(0, endpoint.length() - 1);
        if (!endpoint.startsWith("https://") && !endpoint.startsWith("http://")) {
            Toast.makeText(this, "পুরো ঠিকানা দিন, যেমন https://example.com", Toast.LENGTH_LONG).show();
            return;
        }
        prefs.edit().putString(KEY_ENDPOINT, endpoint).apply();
        Toast.makeText(this, "সেভ হয়েছে", Toast.LENGTH_SHORT).show();
    }

    private void bindVersion() {
        try {
            String version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            ((TextView) findViewById(R.id.version_text)).setText("ভার্সন " + version);
        } catch (PackageManager.NameNotFoundException ignored) {
        }
    }

    // ---------- Styling helpers ----------

    /** Selected option of a segmented control: a raised white thumb, bold text. */
    private void setSegment(TextView item, boolean selected) {
        if (item == null) return;
        item.setBackgroundResource(selected ? R.drawable.seg_thumb : 0);
        item.setTextColor(getColor(selected ? R.color.text_main : R.color.chip_text));
        item.setTypeface(null, selected ? Typeface.BOLD : Typeface.NORMAL);
        item.setSelected(selected);
    }

    /** Multi-select chip: tinted with a check when on, outlined when off. */
    private void setToggleChip(TextView chip, boolean on) {
        chip.setBackgroundResource(on ? R.drawable.chip_selected : R.drawable.chip_unselected);
        chip.setTextColor(getColor(on ? R.color.primary_dark : R.color.text_muted));
        Drawable check = null;
        if (on) {
            check = getDrawable(R.drawable.lc_check).mutate();
            check.setColorFilter(getColor(R.color.primary_dark), PorterDuff.Mode.SRC_IN);
            check.setBounds(0, 0, dp(16), dp(16));
        }
        chip.setCompoundDrawablesRelative(check, null, null, null);
        chip.setSelected(on);
    }

    private void tint(int imageId, int colorRes) {
        ((ImageView) findViewById(imageId)).setColorFilter(getColor(colorRes), PorterDuff.Mode.SRC_IN);
    }

    private GradientDrawable round(int color, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        return d;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
