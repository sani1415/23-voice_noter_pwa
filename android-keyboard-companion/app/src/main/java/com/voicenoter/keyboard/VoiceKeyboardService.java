package com.voicenoter.keyboard;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.graphics.drawable.StateListDrawable;
import android.inputmethodservice.InputMethodService;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.TextUtils;
import android.util.Base64;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.PopupWindow;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;
import android.view.inputmethod.InputConnection;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class VoiceKeyboardService extends InputMethodService {
    private static final String MIME_TYPE = "audio/mp4";
    private static final int TOP_BAR_HEIGHT = 42;
    private static final int GAP_DP = 6;
    private static final int KEY_RADIUS_DP = 9;
    private static final int VOICE_TICK_MS = 70;
    private static final int MAX_PRESETS = 24;

    // Sentinel keys used inside letter rows to flip between the main and "more" page,
    // and to trigger delete / shift behaviour from within a row array.
    private static final String TOK_MORE = "\u0001MORE";
    private static final String TOK_BACK = "\u0001BACK";
    private static final String TOK_DELETE = "\u0001DELETE";
    private static final String TOK_SHIFT = "\u0001SHIFT";
    private static final String TOK_SPACE = "\u0001SPACE";
    private static final String TOK_ENTER = "\u0001ENTER";
    private static final String TOK_LETTERS = "\u0001LETTERS";

    private static final String[] BN_VOWEL_ROW = {
        "\u0985", "\u0986", "\u0987", "\u0988", "\u0989", "\u098A",
        "\u098F", "\u0990", "\u0993", "\u0994"
    };
    // \u09BE \u09BF \u09C0 \u09C1 \u09C2 \u09C3 \u09C7 \u09C8 \u09CB \u09CC \u2014 shown as "<cluster before caret> + sign", committed as the sign alone.
    private static final String[] BN_KAR_SIGNS = {
        "\u09BE", "\u09BF", "\u09C0", "\u09C1", "\u09C2",
        "\u09C3", "\u09C7", "\u09C8", "\u09CB", "\u09CC"
    };
    private static final String DOTTED_CIRCLE = "\u25CC";
    private static final String[] BN_DIGITS = {
        "\u09E7", "\u09E8", "\u09E9", "\u09EA", "\u09EB",
        "\u09EC", "\u09ED", "\u09EE", "\u09EF", "\u09E6"
    };
    private static final String[] ASCII_DIGITS = {"1", "2", "3", "4", "5", "6", "7", "8", "9", "0"};

    // Record mode: 16 kHz mono AAC at 32 kbps is plenty for speech and keeps a
    // 12-minute take (~2.9 MB, ~3.9 MB as base64) under Vercel's 4.5 MB body limit.
    private static final int RECORD_SAMPLE_RATE = 16000;
    private static final int RECORD_BIT_RATE = 32000;
    private static final int MAX_RECORD_MS = 12 * 60 * 1000;
    private static final long MAX_UPLOAD_AUDIO_BYTES = 3_200_000L;
    /** Smaller than this after a failed stop() means nothing usable was captured. */
    private static final long MIN_AUDIO_BYTES = 4096L;
    /** A recording that could not be transcribed yet; survives keyboard restarts. */
    private static final String PENDING_AUDIO_NAME = "voice-keyboard-pending.m4a";

    // Long-press alternate characters for the roman QWERTY rows (EN + phonetic BN),
    // native Bangla letters, and Arabic letters with related forms.
    private static final java.util.Map<String, String[]> EN_LONG_PRESS = new java.util.HashMap<>();
    private static final java.util.Map<String, String[]> BN_LONG_PRESS = new java.util.HashMap<>();
    private static final java.util.Map<String, String[]> AR_LONG_PRESS = new java.util.HashMap<>();
    static {
        EN_LONG_PRESS.put("a", new String[]{"\u00E1", "\u00E0", "\u00E2", "\u00E4", "\u00E5", "\u00E3"});
        EN_LONG_PRESS.put("e", new String[]{"\u00E9", "\u00E8", "\u00EA", "\u00EB"});
        EN_LONG_PRESS.put("i", new String[]{"\u00ED", "\u00EC", "\u00EE", "\u00EF"});
        EN_LONG_PRESS.put("o", new String[]{"\u00F3", "\u00F2", "\u00F4", "\u00F6", "\u00F5"});
        EN_LONG_PRESS.put("u", new String[]{"\u00FA", "\u00F9", "\u00FB", "\u00FC"});
        EN_LONG_PRESS.put("n", new String[]{"\u00F1"});
        EN_LONG_PRESS.put("c", new String[]{"\u00E7"});
        EN_LONG_PRESS.put("s", new String[]{"\u00DF", "\u00A7"});
        EN_LONG_PRESS.put("y", new String[]{"\u00FD"});
        EN_LONG_PRESS.put("'", new String[]{"\u2018", "\u2019", "\u201C", "\u201D", "`"});
        BN_LONG_PRESS.put("\u09A4", new String[]{"\u09CE"});
        BN_LONG_PRESS.put("\u0982", new String[]{"\u0983", "\u0981"});
        BN_LONG_PRESS.put("\u098a", new String[]{"\u098b"}); // \u098a \u2192 \u098b, its neighbour in the alphabet
        BN_LONG_PRESS.put("\u0964", new String[]{"?", "!", "\u0965", ":", ";"});
        // Hold a Bangla digit for the ASCII one (OTP / phone fields often reject \u09E7\u09E8\u09E9).
        for (int i = 0; i < BN_DIGITS.length; i++) {
            BN_LONG_PRESS.put(BN_DIGITS[i], new String[]{ASCII_DIGITS[i]});
        }
        // Hold ا / و / ي for hamza and alef variants (instead of burying them on more).
        AR_LONG_PRESS.put("\u0627", new String[]{"\u0623", "\u0625", "\u0622", "\u0621", "\u0671"});
        AR_LONG_PRESS.put("\u0648", new String[]{"\u0624"});
        AR_LONG_PRESS.put("\u064A", new String[]{"\u0626", "\u0649"});
        AR_LONG_PRESS.put("\u0647", new String[]{"\u0629"});
        AR_LONG_PRESS.put("\u0644", new String[]{"\u0644\u0627", "\u0644\u0623", "\u0644\u0625", "\u0644\u0622"});
    }

    // Theme colours, resolved from the selected preset in applyTheme().
    private int themeBg, themeKeyBg, themeKeyText, themeFnBg, themeFnText;
    private int themeKarBg, themeKarText, themeAccent, themeOnAccent, themeAccentTint, themeAccentStrong;
    private int themeMuted, themeHint, themeShadow, themeSurface, themeSurfaceAlt, themeLiveIdleBg;
    private int themeRecTint, themeRec, themeRecText, themePendingTint, themePendingText;
    private int themeBusyTint, themeBusyText;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private View activeDeleteSource;
    private final Runnable repeatDeleteRunnable = new Runnable() {
        @Override
        public void run() {
            performSingleDelete(activeDeleteSource);
            mainHandler.postDelayed(this, 55);
        }
    };
    private MediaRecorder recorder;
    private File audioFile;
    /** Recording kept after a failed or interrupted transcription; mic tap retries it. */
    private File pendingAudioFile;
    /** Transcript that arrived after its text field was gone; mic tap inserts it. */
    private String pendingTranscript;
    /** The field wants digits: its input type says so, or its placeholder does. */
    private boolean numericField = false;
    /** The numpad was opened by hand (long-press ১২৩) in an ordinary text field. */
    private boolean numpadRequested = false;
    private TextView numpadTag;
    private boolean passwordField = false;
    private LinearLayout bnRow1Layout;
    // Top bar: one row whose content follows the situation (see refreshTopBar()).
    private LinearLayout topBar;
    private LinearLayout toolsGroup, suggestGroup, recordGroup, pendingGroup, numpadGroup;
    private TextView statusPill, pendingLabel, recordTimer, recordDoneLabel, toolAll, toolKar;
    private ImageView pendingIcon, recordDoneIcon, micPillIcon;
    private ImageButton toolPresets, toolSettings, toolCollapse;
    private FrameLayout toolDelete, recordCancel, presetComposeDelete;
    private LinearLayout micPill;
    private LinearLayout voiceNotice;
    private TextView voiceNoticeText, voiceNoticeAction;
    private boolean voiceNoticeNeedsLogin;
    private TextView micPillLabel;
    private View micLiveDot, recordDot;
    private LevelWaveView waveView;
    private ObjectAnimator recordDotPulse;
    /** The ▦ button asked for tools instead of suggestions, until the text changes. */
    private boolean toolsRequested = false;
    private boolean suggestionsShown = false;
    private Button moreKey;
    private TextView rightPunctHint, enterLabel;
    private ImageView enterIcon;
    private FrameLayout enterKey;
    private LinearLayout bottomRowView;
    /** Preset being edited in the compose row, or -1 when adding a new one. */
    private int presetEditIndex = -1;
    private LinearLayout keyboardPanel;
    private LinearLayout presetComposeBar;
    private EditText presetComposeInput;
    private LinearLayout letterContainer;
    private final TextView[] suggestionViews = new TextView[3];
    // Best suggestion goes to the centre slot, second-best left, third right.
    private static final int[] SUGGESTION_SLOT_ORDER = {1, 0, 2};
    private volatile BanglaDictionary bnDictionary;
    private boolean bnDictionaryLoadStarted = false;
    private Button shiftKeyView;
    private Button rightPunctButton;
    private Button spaceKeyButton;
    private final java.util.List<Button> qwertyLetterButtons = new java.util.ArrayList<>();
    private java.util.List<String> enabledLangs = new java.util.ArrayList<>();
    private boolean morePage = false;
    private boolean presetComposeActive = false;
    private String appliedConfigSig = "";
    private SonioxLiveTranscriber liveTranscriber;
    private volatile boolean isRecording = false;
    private volatile boolean isLiveActive = false;
    private volatile boolean isLiveConnecting = false;
    private volatile boolean isTranscribing = false;
    private long voiceActivityStartMs = 0;
    private int voiceSessionGeneration = 0;
    private int liveStartOffset = 0;
    private String liveLastText = "";
    private String liveAnchorFinalBase = "";
    /**
     * After a user edit during live, STT text at edit-time is frozen. Only the delta
     * beyond this freeze is shown live (so deleted words are not restored, but new
     * speech still streams immediately).
     */
    private String liveFrozenFinal = null;
    private String liveFrozenPartial = "";
    /** Last final/partial received from STT — the freeze point for manual edits. */
    private String liveLastFinalSeen = "";
    private String liveLastPartialSeen = "";
    /** User moved caret / selected text elsewhere while live — insert next speech there. */
    private boolean livePendingRelocate = false;
    private int liveRelocateSelStart = 0;
    private int liveRelocateSelEnd = 0;
    private boolean liveIgnoreSelectionCallback = false;
    /**
     * Caret positions our own composing updates put the cursor at. Editor callbacks
     * report these late, so matching ones must not be mistaken for user caret moves.
     */
    private final java.util.ArrayDeque<Integer> liveExpectedCarets = new java.util.ArrayDeque<>();
    private boolean voiceOnlyMode = false;
    private long lastWarmUpMs = 0;
    private String layoutLang = MainActivity.LANG_BN;
    private String voiceInputMode = MainActivity.MODE_RECORD;
    private String bnKarBase = null;
    private boolean bnRow1Vowels = true;

    // Comfort settings.
    private boolean hapticEnabled = true;
    private int letterKeyHeightDp = 42;
    private int bottomKeyHeightDp = 50;
    private int shiftState = 0; // 0 = off, 1 = shift-once, 2 = caps-lock
    private boolean lastKeyWasSpace = false;
    private long lastSpaceCommitMs = 0;
    private EditorInfo currentEditorInfo;

    @Override
    public void onCreate() {
        super.onCreate();
        File pending = new File(getFilesDir(), PENDING_AUDIO_NAME);
        if (pending.exists() && pending.length() > 0) pendingAudioFile = pending;
    }

    @Override
    public View onCreateInputView() {
        loadPreferences();
        prefetchSonioxKeyIfNeeded();
        loadBnDictionaryIfNeeded();
        return buildInputView();
    }

    @Override
    public void onStartInputView(EditorInfo attribute, boolean restarting) {
        super.onStartInputView(attribute, restarting);
        currentEditorInfo = attribute;
        int inputType = attribute != null ? attribute.inputType : 0;
        numericField = isNumericInputType(inputType) || (attribute != null
            && NumericFieldHint.looksNumeric(inputType, attribute.hintText, attribute.label));
        if (!restarting) numpadRequested = false;
        passwordField = isPasswordInputType(inputType);
        if (settingsChangedSinceBuild()) {
            loadPreferences();
            morePage = numericField;
            setInputView(buildInputView());
        } else {
            // Settings Activity can change Record/Live while the IME process stays alive.
            // Always re-read that so the mic label matches the saved preference.
            reloadVoiceInputModeFromPrefs();
            // A new field opens on letters, or straight on the digit page for
            // number / phone / date fields. Restarts of the same field keep the page.
            if (!restarting && morePage != numericField) {
                morePage = numericField;
                resetBnKarState();
                rebuildLetterRows();
            }
        }
        refreshEnterKeyLabel();
        updateMicButtonAppearance();
        prefetchSonioxKeyIfNeeded();
        loadBnDictionaryIfNeeded();
        onTextContextChanged();
        if (!SupabaseAuth.isSignedIn(this) && !passwordField) {
            showVoiceNotice("ভয়েস দিয়ে লিখতে লগইন করুন।", true);
        } else if (voiceNoticeNeedsLogin) {
            clearVoiceNotice();
        }
    }

    private static boolean isNumericInputType(int inputType) {
        int cls = inputType & InputType.TYPE_MASK_CLASS;
        return cls == InputType.TYPE_CLASS_NUMBER
            || cls == InputType.TYPE_CLASS_PHONE
            || cls == InputType.TYPE_CLASS_DATETIME;
    }

    private static boolean isPasswordInputType(int inputType) {
        int cls = inputType & InputType.TYPE_MASK_CLASS;
        int variation = inputType & InputType.TYPE_MASK_VARIATION;
        if (cls == InputType.TYPE_CLASS_TEXT) {
            return variation == InputType.TYPE_TEXT_VARIATION_PASSWORD
                || variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                || variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD;
        }
        return cls == InputType.TYPE_CLASS_NUMBER
            && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD;
    }

    private void reloadVoiceInputModeFromPrefs() {
        SharedPreferences prefs = getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE);
        String mode = prefs.getString(MainActivity.KEY_VOICE_INPUT_MODE, MainActivity.MODE_RECORD);
        if (!MainActivity.MODE_RECORD.equals(mode) && !MainActivity.MODE_LIVE.equals(mode)) {
            mode = MainActivity.MODE_RECORD;
        }
        voiceInputMode = mode;
        hapticEnabled = prefs.getBoolean(MainActivity.KEY_HAPTIC, true);
    }

    private boolean settingsChangedSinceBuild() {
        SharedPreferences prefs = getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE);
        String sig = configSignature(
            prefs.getString(MainActivity.KEY_THEME, MainActivity.DEFAULT_THEME),
            prefs.getString(MainActivity.KEY_ENABLED_LANGS, MainActivity.DEFAULT_ENABLED_LANGS),
            prefs.getBoolean(MainActivity.KEY_HAPTIC, true),
            prefs.getString(MainActivity.KEY_KEY_SIZE, MainActivity.DEFAULT_KEY_SIZE),
            prefs.getString(MainActivity.KEY_VOICE_INPUT_MODE, MainActivity.MODE_RECORD)
        );
        return !sig.equals(appliedConfigSig);
    }

    private String configSignature(String theme, String enabledRaw, boolean haptic,
                                     String size, String voiceMode) {
        return theme + "|" + enabledRaw + "|" + haptic + "|" + size + "|" + voiceMode;
    }

    @Override
    public void onFinishInput() {
        finalizePendingComposing();
        stopAllVoiceActivity(true);
        super.onFinishInput();
    }

    @Override
    public void onFinishInputView(boolean finishingInput) {
        finalizePendingComposing();
        stopAllVoiceActivity(true);
        super.onFinishInputView(finishingInput);
    }

    @Override
    public void onWindowHidden() {
        dismissGlobePopup();
        dismissSymbolPopup();
        dismissPresetPopup();
        hidePresetCompose();
        finalizePendingComposing();
        stopAllVoiceActivity(true);
        super.onWindowHidden();
    }

    private void stopAllVoiceActivity(boolean cancelPendingWork) {
        if (cancelPendingWork) {
            voiceSessionGeneration++;
        }
        if (isLiveActive || liveTranscriber != null) {
            stopLive();
        }
        if (isRecording) {
            // The field is going away, so there is nowhere to type the result.
            // Keep the take instead of throwing it away; a mic tap sends it later.
            File finished = stopRecordingToFile();
            if (finished != null) {
                keepAsPendingAudio(finished);
                setStatus("রেকর্ডিং রাখা আছে — মাইকে চাপলে লেখা হবে");
            }
        }
    }

    /** Long-press set of the bottom-row full-stop key: the question mark first. */
    private static String[] bottomPunctAltsFor(String lang) {
        if (MainActivity.LANG_BN.equals(lang)) {
            return new String[]{"?", "!", "॥", ".", ":", ";", "\"", "'", "-", "…"};
        }
        if (MainActivity.LANG_AR.equals(lang)) {
            return new String[]{"؟", "!", "،", "؛", ":", ".", "\"", "'", "-", "…"};
        }
        return new String[]{"?", "!", ",", ":", ";", "\"", "'", "-", "@", "…"};
    }

    private void handleSpaceTap(View source) {
        performKeyHaptic(source);
        long now = System.currentTimeMillis();
        if (!presetComposeActive && lastKeyWasSpace && (now - lastSpaceCommitMs) <= 500) {
            InputConnection ic = getCurrentInputConnection();
            if (ic != null) {
                CharSequence before = ic.getTextBeforeCursor(2, 0);
                if (before != null && before.length() == 2 && before.charAt(1) == ' '
                    && endsWord(before.charAt(0))) {
                    ic.deleteSurroundingText(1, 0);
                    boolean dari = MainActivity.LANG_BN.equals(layoutLang);
                    commitText(dari ? "\u0964 " : ". ");
                    lastKeyWasSpace = false;
                    lastSpaceCommitMs = 0;
                    return;
                }
            }
        }
        commitText(" ");
        lastKeyWasSpace = true;
        lastSpaceCommitMs = now;
    }

    /**
     * Whether {@code c} can end a word. Bengali vowel signs and ং/ঁ are combining
     * marks, not letters, yet most Bangla words end in one ("আমি", "বাংলা").
     */
    private static boolean endsWord(char c) {
        if (Character.isLetterOrDigit(c)) return true;
        int type = Character.getType(c);
        return type == Character.NON_SPACING_MARK || type == Character.COMBINING_SPACING_MARK;
    }

    private boolean isRomanQwertyMode() {
        return MainActivity.LANG_EN.equals(layoutLang);
    }

    private boolean bnDynamicRow1Enabled() {
        return MainActivity.LANG_BN.equals(layoutLang) && !morePage;
    }

    private String[] row1Keys() {
        if (bnRow1Vowels) return BN_VOWEL_ROW;
        String base = bnKarBase != null ? bnKarBase : DOTTED_CIRCLE;
        String[] keys = new String[BN_KAR_SIGNS.length];
        for (int i = 0; i < BN_KAR_SIGNS.length; i++) {
            keys[i] = base + BN_KAR_SIGNS[i];
        }
        return keys;
    }

    private void resetBnKarState() {
        bnKarBase = null;
        bnRow1Vowels = true;
    }

    /** Kar toggle: flip the row by hand until the text before the caret changes again. */
    private void toggleBnVowelRow() {
        if (!bnDynamicRow1Enabled()) return;
        bnRow1Vowels = !bnRow1Vowels;
        refreshBnRow1();
    }

    private void refreshBnRow1() {
        if (bnRow1Layout == null) return;
        bnRow1Layout.removeAllViews();
        populateKeyRow(bnRow1Layout, row1Keys(), true, false);
        updateKarToggleHighlight();
    }

    /**
     * Row 1 follows the text: kar signs right after a consonant (or conjunct),
     * independent vowels anywhere else. Reading the real text, instead of
     * remembering the last consonant key, keeps the row right after space,
     * delete, cursor moves and voice input.
     */
    private void syncBnRow1WithText() {
        if (!bnDynamicRow1Enabled() || bnRow1Layout == null) return;
        String base = bnKarBaseBeforeCursor();
        boolean vowels = base == null;
        if (vowels == bnRow1Vowels && TextUtils.equals(base, bnKarBase)) return;
        bnKarBase = base;
        bnRow1Vowels = vowels;
        refreshBnRow1();
    }

    /** The consonant cluster right before the caret ("ক", "ক্ষ", "ব্য", "য়"), or null. */
    private String bnKarBaseBeforeCursor() {
        CharSequence before;
        if (presetComposeActive && presetComposeInput != null) {
            int caret = presetComposeInput.getSelectionStart();
            if (caret < 0 || caret != presetComposeInput.getSelectionEnd()) return null;
            CharSequence all = presetComposeInput.getText();
            before = all.subSequence(Math.max(0, caret - 12), caret);
        } else {
            InputConnection ic = getCurrentInputConnection();
            if (ic == null) return null;
            CharSequence selected = ic.getSelectedText(0);
            if (selected != null && selected.length() > 0) return null;
            before = ic.getTextBeforeCursor(12, 0);
        }
        if (before == null) return null;
        int end = before.length();
        int i = end;
        if (i > 0 && before.charAt(i - 1) == '\u09BC') i--; // nukta: য় ড় ঢ় typed decomposed
        if (i == 0 || !isBnConsonant(before.charAt(i - 1))) return null;
        i--;
        // Include up to two "consonant + hasanta" links so the key shows the whole conjunct.
        for (int links = 0; links < 2 && i >= 2
            && before.charAt(i - 1) == '\u09CD' && isBnConsonant(before.charAt(i - 2)); links++) {
            i -= 2;
        }
        return before.subSequence(i, end).toString();
    }

    private static boolean isBnConsonant(char c) {
        return (c >= '\u0995' && c <= '\u09B9') || c == '\u09DC' || c == '\u09DD' || c == '\u09DF';
    }

    private void onRow1KeyPressed(String key) {
        if (bnDynamicRow1Enabled() && !bnRow1Vowels) {
            // The key shows "cluster + sign" but only the sign is typed: the cluster
            // is already in the text, so nothing has to be deleted first.
            commitText(key.substring(key.length() - 1));
            return;
        }
        commitText(key);
    }

    private void onLetterKeyPressed(String key) {
        commitText(key);
    }

    private void onQwertyLetterPressed(Button button) {
        String label = button.getText().toString();
        commitText(label);
        if (shiftState == 1) {
            shiftState = 0;
            refreshShiftVisuals();
        }
    }

    /** Text before the caret changed: refresh everything that reads it. */
    private void onTextContextChanged() {
        toolsRequested = false;
        refreshSuggestions();
        syncBnRow1WithText();
    }

    private void finalizePendingComposing() {
        // No-op: phonetic composing removed.
    }

    // ---------- Word suggestions ----------

    private void loadBnDictionaryIfNeeded() {
        if (bnDictionary != null || bnDictionaryLoadStarted) return;
        bnDictionaryLoadStarted = true;
        executor.execute(() -> {
            try {
                BanglaDictionary dict = BanglaDictionary.load(this);
                mainHandler.post(() -> {
                    bnDictionary = dict;
                    refreshSuggestions();
                });
            } catch (Exception e) {
                mainHandler.post(() -> bnDictionaryLoadStarted = false);
            }
        });
    }

    /** Bengali word fragment immediately before the cursor, or "" when mid-word/selection. */
    private String currentBnPartialWord() {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return "";
        CharSequence selected = ic.getSelectedText(0);
        if (selected != null && selected.length() > 0) return "";
        CharSequence after = ic.getTextAfterCursor(1, 0);
        if (after != null && after.length() > 0 && isBnWordChar(after.charAt(0))) return "";
        CharSequence before = ic.getTextBeforeCursor(32, 0);
        if (before == null) return "";
        int i = before.length();
        while (i > 0 && isBnWordChar(before.charAt(i - 1))) i--;
        return before.subSequence(i, before.length()).toString();
    }

    private boolean isBnWordChar(char c) {
        if (c >= '\u09E6' && c <= '\u09EF') return false; // Bengali digits end a word
        return (c >= '\u0980' && c <= '\u09FF') || c == '\u200C' || c == '\u200D';
    }

    private void applySuggestion(String word) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        // Deleting into an active composing span corrupts the live text.
        if (isLiveActive && !liveLastText.isEmpty()) {
            finishLiveComposingAtCaret(ic);
        }
        String partial = currentBnPartialWord();
        if (partial.isEmpty()) return;
        ic.deleteSurroundingText(partial.length(), 0);
        commitText(word + " ");
    }

    @Override
    public void onUpdateSelection(int oldSelStart, int oldSelEnd, int newSelStart, int newSelEnd,
                                  int candidatesStart, int candidatesEnd) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd,
            candidatesStart, candidatesEnd);
        onTextContextChanged();
        trackLiveSelectionChange(newSelStart, newSelEnd);
    }

    /**
     * Detect when the user taps or selects text while live. The dictated span is
     * sealed immediately (so later updates stop yanking the caret back to it) and
     * the next speech is inserted at the new caret, replacing any selection.
     */
    private void trackLiveSelectionChange(int newSelStart, int newSelEnd) {
        if (!isLiveActive || liveIgnoreSelectionCallback) return;
        if (newSelStart < 0 || newSelEnd < 0) return;

        int start = Math.min(newSelStart, newSelEnd);
        int end = Math.max(newSelStart, newSelEnd);

        if (start == end) {
            // Callbacks from our own composing updates land on a recent span end.
            if (isRecentExpectedCaret(start)) return;
            // Empty live segment: caret staying on the anchor is normal.
            if (liveLastText.isEmpty() && start == liveStartOffset) return;
        }

        sealLiveSpan(getCurrentInputConnection());
        freezeLiveAtCurrent();
        livePendingRelocate = true;
        liveRelocateSelStart = start;
        liveRelocateSelEnd = end;
    }

    /** Freeze STT output at the last received text — only newer speech gets inserted. */
    private void freezeLiveAtCurrent() {
        liveFrozenFinal = liveLastFinalSeen;
        liveFrozenPartial = liveLastPartialSeen;
    }

    private void rememberExpectedCaret(int pos) {
        liveExpectedCarets.addLast(pos);
        while (liveExpectedCarets.size() > 4) liveExpectedCarets.pollFirst();
    }

    private boolean isRecentExpectedCaret(int pos) {
        return liveExpectedCarets.contains(pos);
    }

    // ---------- Shift / caps lock ----------

    private boolean shiftActive() {
        return shiftState != 0;
    }

    private void cycleShift() {
        if (shiftState == 0) shiftState = 1;
        else if (shiftState == 1) shiftState = 2;
        else shiftState = 0;
        refreshShiftVisuals();
    }

    private String[][] moreRowsFor(String lang) {
        // No separate Back key — bottom-left more key toggles back to page 1.
        // Keep delete on the last row (same corner as page 1).
        String[][] nums = numberRowsFor(lang);
        if (MainActivity.LANG_BN.equals(lang) && !numericField) {
            return new String[][]{
                nums[0],
                nums[1],
                nums[2],
                // ং ঃ ঁ ৎ ঋ ৃ ॥ ‘ ’ …
                {"\u0982", "\u0983", "\u0981", "\u09CE", "\u098B", "\u09C3", "\u0965", "\u2018", "\u2019", "\u2026"},
                {"\u20AC", "<", ">", "[", "]", "{", "}", "~", "|", TOK_DELETE},
            };
        }
        return new String[][]{
            nums[0],
            nums[1],
            nums[2],
            {"<", ">", "[", "]", "{", "}", "^", "~", "`", TOK_DELETE},
        };
    }

    private String[][] numberRowsFor(String lang) {
        String[] symbolsA = {"@", "#", "$", "%", "&", "*", "-", "+", "(", ")"};
        String[] symbolsB = {"!", "\"", "'", ":", ";", "/", "?", "_", ".", "="};
        // Number / phone / date fields always get ASCII digits: many apps (OTP,
        // bKash, phone numbers) reject Bangla or Arabic-Indic digits.
        if (numericField) {
            return new String[][]{ASCII_DIGITS, symbolsA, symbolsB};
        }
        if (MainActivity.LANG_BN.equals(lang)) {
            return new String[][]{
                BN_DIGITS,
                symbolsA,
                // ৳ ? ! : ; " ' / । =
                {"\u09F3", "?", "!", ":", ";", "\"", "'", "/", "\u0964", "="},
            };
        }
        if (MainActivity.LANG_AR.equals(lang)) {
            return new String[][]{
                {"\u0661", "\u0662", "\u0663", "\u0664", "\u0665", "\u0666", "\u0667", "\u0668", "\u0669", "\u0660"},
                symbolsA,
                // Arabic ؛ ؟ ، in place of the Latin ; ? .
                {"!", "\"", "'", ":", "\u061B", "/", "\u061F", "_", "\u060C", "="},
            };
        }
        return new String[][]{
            {"1", "2", "3", "4", "5", "6", "7", "8", "9", "0"},
            symbolsA,
            symbolsB,
        };
    }

    private String[][] layoutRowsFor(String lang) {
        if (isRomanQwertyMode()) {
            return new String[][]{
                {"q", "w", "e", "r", "t", "y", "u", "i", "o", "p"},
                {"a", "s", "d", "f", "g", "h", "j", "k", "l", "'"},
                {TOK_SHIFT, "z", "x", "c", "v", "b", "n", "m", TOK_DELETE},
            };
        }
        if (MainActivity.LANG_AR.equals(lang)) {
            // د ز ظ on the main page; ء/أ/إ/… come from long-press on ا و ي ه ل.
            return new String[][]{
                {"\u0636", "\u0635", "\u062B", "\u0642", "\u0641", "\u063A", "\u0639", "\u0647", "\u062E", "\u062D"},
                {"\u062C", "\u0634", "\u0633", "\u064A", "\u0628", "\u0644", "\u0627", "\u062A", "\u0646", "\u0645"},
                {"\u0643", "\u0637", "\u0638", "\u0630", "\u062F", "\u0632", "\u0631", "\u0649", "\u0629", "\u0648", TOK_DELETE},
            };
        }
        return new String[][]{
            {"\u0985", "\u0986", "\u0987", "\u0988", "\u0989", "\u098A", "\u098F", "\u0990", "\u0993", "\u0994"},
            {"\u0995", "\u0996", "\u0997", "\u0998", "\u0999", "\u099A", "\u099B", "\u099C", "\u099D", "\u099E"},
            {"\u099F", "\u09A0", "\u09A1", "\u09A2", "\u09A3", "\u09A4", "\u09A5", "\u09A6", "\u09A7", "\u09A8"},
            {"\u09AA", "\u09AB", "\u09AC", "\u09AD", "\u09AE", "\u09AF", "\u09B0", "\u09B2", "\u09B6", "\u09B7"},
            // ং replaces a second comma (the bottom row already has one); hold it for ঃ ঁ.
            {"\u09B8", "\u09B9", "\u09DC", "\u09DD", "\u09CD\u09AF", "\u09AF\u09BC", "\u0982", "\u09CD", "\u0964", TOK_DELETE},
        };
    }

    private PopupWindow globePopup;
    private PopupWindow symbolPopup;
    private PopupWindow presetPopup;

    private java.util.List<String> loadPresets() {
        java.util.List<String> list = new java.util.ArrayList<>();
        try {
            String raw = getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)
                .getString(MainActivity.KEY_PRESETS, MainActivity.DEFAULT_PRESETS);
            JSONArray arr = new JSONArray(raw != null ? raw : "[]");
            for (int i = 0; i < arr.length(); i++) {
                String item = arr.optString(i, "").trim();
                if (!item.isEmpty()) list.add(item);
            }
        } catch (Exception ignored) {
        }
        return list;
    }

    private void savePresets(java.util.List<String> presets) {
        JSONArray arr = new JSONArray();
        for (String preset : presets) {
            if (preset != null && !preset.trim().isEmpty()) {
                arr.put(preset.trim());
            }
        }
        getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(MainActivity.KEY_PRESETS, arr.toString())
            .apply();
    }

    @Override
    public void onDestroy() {
        voiceSessionGeneration++;
        stopLiveQuietly();
        if (isRecording) {
            File finished = stopRecordingToFile();
            if (finished != null) keepAsPendingAudio(finished);
        }
        mainHandler.removeCallbacks(repeatDeleteRunnable);
        mainHandler.removeCallbacks(voiceTickRunnable);
        executor.shutdownNow();
        super.onDestroy();
    }

    private void toggleVoiceInputMode() {
        voiceInputMode = MainActivity.MODE_LIVE.equals(voiceInputMode)
            ? MainActivity.MODE_RECORD
            : MainActivity.MODE_LIVE;
        savePreferences();
        updateMicButtonAppearance();
        prefetchSonioxKeyIfNeeded();
        Toast.makeText(this,
            MainActivity.MODE_LIVE.equals(voiceInputMode)
                ? "লাইভ মোড: বলার সাথে সাথে লেখা"
                : "রেকর্ড মোড: বলা শেষে লেখা",
            Toast.LENGTH_SHORT).show();
    }

    private void toggleVoiceInput() {
        finalizePendingComposing();
        if (isTranscribing) {
            // Starting another take now would orphan the transcript still on its way.
            setStatus("আগের রেকর্ডিং লেখা হচ্ছে, একটু অপেক্ষা করুন");
            return;
        }
        boolean idle = !isRecording && !isLiveActive;
        if (idle && pendingTranscript != null) {
            insertPendingTranscript();
            return;
        }
        if (idle && pendingAudioFile != null) {
            retryPendingAudio();
            return;
        }
        if (idle && passwordField) {
            setStatus("পাসওয়ার্ডের ঘরে ভয়েস বন্ধ");
            return;
        }
        if (MainActivity.MODE_LIVE.equals(voiceInputMode)) {
            if (isLiveActive) stopLive();
            else startLive();
        } else if (isRecording) {
            stopAndTranscribe();
        } else {
            startRecording();
        }
    }

    private void startLive() {
        if (isLiveActive || isRecording) return;
        if (!SupabaseAuth.isSignedIn(this)) {
            if (VoiceDiagnostics.summary(this).isEmpty()) {
                VoiceDiagnostics.record(this, "soniox_key", "auth_required", 0);
            }
            showVoiceNotice("ভয়েস দিয়ে লিখতে আবার লগইন করুন।", true);
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
            && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            setStatus("অ্যাপে মাইকের অনুমতি দিন");
            return;
        }

        String endpoint = getEndpoint();
        if (endpoint.isEmpty()) {
            setStatus("অ্যাপে সার্ভারের ঠিকানা দিন");
            return;
        }

        clearVoiceNotice();
        isLiveActive = true;
        isLiveConnecting = true;
        beginLiveAnchor();
        final int session = ++voiceSessionGeneration;
        updateMicButtonAppearance();

        liveTranscriber = new SonioxLiveTranscriber();
        liveTranscriber.start(this, endpoint, layoutLang, new SonioxLiveTranscriber.Listener() {
            @Override
            public void onConnecting() {
                mainHandler.post(() -> {
                    if (session != voiceSessionGeneration || !isLiveActive) return;
                    isLiveConnecting = true;
                    updateMicButtonAppearance();
                });
            }

            @Override
            public void onListening() {
                mainHandler.post(() -> {
                    if (session != voiceSessionGeneration || !isLiveActive) return;
                    isLiveConnecting = false;
                    startVoiceTimer();
                    updateMicButtonAppearance();
                });
            }

            @Override
            public void onTranscriptUpdate(String finalText, String partialText) {
                mainHandler.post(() -> {
                    if (session != voiceSessionGeneration || !isLiveActive) return;
                    if (isLiveConnecting) {
                        isLiveConnecting = false;
                        startVoiceTimer();
                        updateMicButtonAppearance();
                    }
                    updateLiveInsert(VoicePunctuation.apply(finalText), partialText);
                });
            }

            @Override
            public void onError(String message) {
                if (session != voiceSessionGeneration) return;
                mainHandler.post(() -> {
                    if (session != voiceSessionGeneration) return;
                    isLiveConnecting = false;
                    isLiveActive = false;
                    stopVoiceTimer();
                    liveTranscriber = null;
                    updateMicButtonAppearance();
                    showVoiceNotice(message, !SupabaseAuth.isSignedIn(VoiceKeyboardService.this)
                        || message.contains("লগইন করুন"));
                });
            }

            @Override
            public void onStopped() {
                mainHandler.post(() -> {
                    if (session != voiceSessionGeneration) return;
                    isLiveConnecting = false;
                    isLiveActive = false;
                    stopVoiceTimer();
                    liveTranscriber = null;
                    updateMicButtonAppearance();
                });
            }
        });
    }

    private void stopLive() {
        if (!isLiveActive && liveTranscriber == null) return;
        isLiveActive = false;
        isLiveConnecting = false;
        stopVoiceTimer();
        finalizeLiveInsert();
        SonioxLiveTranscriber active = liveTranscriber;
        liveTranscriber = null;
        updateMicButtonAppearance();
        if (active != null) active.stop();
    }

    private void stopLiveQuietly() {
        isLiveActive = false;
        isLiveConnecting = false;
        stopVoiceTimer();
        finalizeLiveInsert();
        if (liveTranscriber != null) {
            liveTranscriber.stop();
        }
        liveTranscriber = null;
    }

    private void beginLiveAnchor() {
        liveLastText = "";
        liveAnchorFinalBase = "";
        liveFrozenFinal = null;
        liveFrozenPartial = "";
        liveLastFinalSeen = "";
        liveLastPartialSeen = "";
        livePendingRelocate = false;
        liveExpectedCarets.clear();
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) {
            liveStartOffset = 0;
            return;
        }
        ExtractedTextRequest req = new ExtractedTextRequest();
        req.hintMaxChars = 100000;
        ExtractedText et = ic.getExtractedText(req, 0);
        if (et != null && et.text != null && et.selectionStart >= 0) {
            int selA = et.startOffset + Math.min(et.selectionStart, et.selectionEnd);
            int selB = et.startOffset + Math.max(et.selectionStart, et.selectionEnd);
            liveStartOffset = selA;
            if (selB > selA) {
                // Dictation replaces the selection — but only once speech actually
                // arrives, so a failed session doesn't destroy the selected text.
                livePendingRelocate = true;
                liveRelocateSelStart = selA;
                liveRelocateSelEnd = selB;
                liveFrozenFinal = "";
                liveFrozenPartial = "";
            }
        } else {
            liveStartOffset = getReliableCursorOffset(ic);
        }
    }

    private int getCursorOffset(InputConnection ic) {
        CharSequence before = ic.getTextBeforeCursor(100000, 0);
        return before != null ? before.length() : 0;
    }

    /**
     * Cursor offset that works during composing. {@link InputConnection#getTextBeforeCursor}
     * often excludes the composing region, so it reports the span start while the caret
     * looks like it is at the end — which makes delete jump backward.
     */
    private int getReliableCursorOffset(InputConnection ic) {
        ExtractedTextRequest req = new ExtractedTextRequest();
        req.hintMaxChars = 100000;
        ExtractedText et = ic.getExtractedText(req, 0);
        if (et != null && et.text != null && et.selectionStart >= 0) {
            return et.startOffset + et.selectionStart;
        }
        if (!liveLastText.isEmpty()) {
            // Visual caret is almost always at the end of the live composing span.
            return liveStartOffset + liveLastText.length();
        }
        return getCursorOffset(ic);
    }

    /** True when the user deleted/changed text inside the live composing span. */
    private boolean hasLiveSegmentBeenEdited(InputConnection ic) {
        if (liveLastText == null || liveLastText.isEmpty()) return false;
        ExtractedTextRequest req = new ExtractedTextRequest();
        req.hintMaxChars = 100000;
        ExtractedText et = ic.getExtractedText(req, 0);
        if (et == null || et.text == null) return false;
        String text = et.text.toString();
        int start = liveStartOffset;
        if (start < 0 || start > text.length()) return true;
        if (start + liveLastText.length() > text.length()) return true;
        return !text.substring(start, start + liveLastText.length()).equals(liveLastText);
    }

    /**
     * Commit the current live composing text without moving the caret to the span
     * start. An active selection is kept as-is so it can still be replaced.
     */
    private void finishLiveComposingAtCaret(InputConnection ic) {
        if (ic == null) return;
        int selStart;
        int selEnd;
        ExtractedTextRequest req = new ExtractedTextRequest();
        req.hintMaxChars = 100000;
        ExtractedText et = ic.getExtractedText(req, 0);
        if (et != null && et.text != null && et.selectionStart >= 0) {
            selStart = et.startOffset + Math.min(et.selectionStart, et.selectionEnd);
            selEnd = et.startOffset + Math.max(et.selectionStart, et.selectionEnd);
        } else {
            selStart = liveStartOffset + liveLastText.length();
            selEnd = selStart;
        }
        liveIgnoreSelectionCallback = true;
        ic.beginBatchEdit();
        try {
            ic.finishComposingText();
        } catch (Exception ignored) {
        }
        try {
            ic.setSelection(selStart, selEnd);
        } catch (Exception ignored) {
        }
        ic.endBatchEdit();
        liveLastText = "";
        liveStartOffset = selStart;
        mainHandler.post(() -> liveIgnoreSelectionCallback = false);
        rememberExpectedCaret(selStart);
    }

    /** Commit the live composing span in place without touching the user's caret. */
    private void sealLiveSpan(InputConnection ic) {
        liveLastText = "";
        if (ic == null) return;
        liveIgnoreSelectionCallback = true;
        ic.beginBatchEdit();
        try {
            ic.finishComposingText();
        } catch (Exception ignored) {
        }
        ic.endBatchEdit();
        mainHandler.post(() -> liveIgnoreSelectionCallback = false);
    }

    /**
     * First speech after the user moved the caret or made a selection: replace the
     * selection (or insert at the caret) and continue composing there. Waits until
     * there is actually new speech so the selection isn't destroyed prematurely.
     */
    private void applyPendingRelocate(InputConnection ic, String safeFinal, String safePartial) {
        if (liveFrozenFinal == null) {
            liveFrozenFinal = "";
            liveFrozenPartial = "";
        }
        String delta = computePostEditDelta(safeFinal, safePartial);
        if (delta == null || delta.isEmpty()) return; // nothing new spoken yet

        int selStart = Math.min(liveRelocateSelStart, liveRelocateSelEnd);
        int selEnd = Math.max(liveRelocateSelStart, liveRelocateSelEnd);
        livePendingRelocate = false;

        liveIgnoreSelectionCallback = true;
        ic.beginBatchEdit();
        try {
            ic.setSelection(selStart, selEnd);
            ic.setComposingText(delta, 1);
            liveStartOffset = selStart;
            liveLastText = delta;
        } catch (Exception ignored) {
            liveStartOffset = getReliableCursorOffset(ic);
            liveLastText = "";
        }
        ic.endBatchEdit();
        mainHandler.post(() -> liveIgnoreSelectionCallback = false);
        rememberExpectedCaret(liveStartOffset + liveLastText.length());
    }

    /** A manual edit landed at the caret — future speech continues from there. */
    private void reanchorLiveAtCaretNow(InputConnection ic) {
        if (!isLiveActive || ic == null) return;
        freezeLiveAtCurrent();
        liveStartOffset = getReliableCursorOffset(ic);
        liveLastText = "";
        livePendingRelocate = false;
        rememberExpectedCaret(liveStartOffset);
    }

    private void reanchorAfterUserEdit(InputConnection ic, String finalText, String partialText) {
        finishLiveComposingAtCaret(ic);
        liveStartOffset = getReliableCursorOffset(ic);
        liveLastText = "";
        liveAnchorFinalBase = finalText != null ? finalText : "";
        liveFrozenFinal = finalText != null ? finalText : "";
        liveFrozenPartial = partialText != null ? partialText : "";
        livePendingRelocate = false;
    }

    /**
     * Text beyond the post-edit freeze. Compatible growth streams live; incompatible
     * STT revisions advance the freeze without restoring deleted words.
     */
    private String computePostEditDelta(String safeFinal, String safePartial) {
        if (liveFrozenFinal == null) return null;
        String frozen = liveFrozenFinal + liveFrozenPartial;
        String current = safeFinal + safePartial;
        if (current.startsWith(frozen)) {
            return current.substring(frozen.length());
        }
        // Hypothesis revised (e.g. last word changed). Do not dump the whole string back.
        liveFrozenFinal = safeFinal;
        liveFrozenPartial = safePartial;
        return "";
    }

    private void finalizeLiveInsert() {
        InputConnection ic = getCurrentInputConnection();
        if (ic != null && !liveLastText.isEmpty()) {
            sealLiveSpan(ic);
        }
        liveLastText = "";
        liveAnchorFinalBase = "";
        liveFrozenFinal = null;
        liveFrozenPartial = "";
        liveLastFinalSeen = "";
        liveLastPartialSeen = "";
        livePendingRelocate = false;
        liveExpectedCarets.clear();
    }

    private void updateLiveInsert(String finalText, String partialText) {
        if (!isLiveActive) return;
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;

        String safeFinal = finalText != null ? finalText : "";
        String safePartial = partialText != null ? partialText : "";
        liveLastFinalSeen = safeFinal;
        liveLastPartialSeen = safePartial;

        if (livePendingRelocate) {
            applyPendingRelocate(ic, safeFinal, safePartial);
            return;
        }

        if (hasLiveSegmentBeenEdited(ic)) {
            reanchorAfterUserEdit(ic, safeFinal, safePartial);
            return;
        }

        String displayText;
        if (liveFrozenFinal != null) {
            displayText = computePostEditDelta(safeFinal, safePartial);
            if (displayText == null) displayText = "";
            if (displayText.isEmpty() && liveLastText.isEmpty()) return;
        } else {
            int baseLen = Math.min(liveAnchorFinalBase.length(), safeFinal.length());
            displayText = safeFinal.substring(baseLen) + safePartial;
            if (displayText.isEmpty() && liveLastText.isEmpty()) return;
        }

        liveIgnoreSelectionCallback = true;
        ic.beginBatchEdit();
        ic.setSelection(liveStartOffset, liveStartOffset + liveLastText.length());
        ic.setComposingText(displayText, 1);
        liveLastText = displayText;
        ic.endBatchEdit();
        mainHandler.post(() -> liveIgnoreSelectionCallback = false);
        rememberExpectedCaret(liveStartOffset + displayText.length());
    }

    private String trimStatus(String message) {
        if (message == null || message.isEmpty()) return "লাইভ চালু করা যায়নি";
        return message.length() > 60 ? message.substring(0, 60) : message;
    }

    private void startRecording() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
            && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            setStatus("অ্যাপে মাইকের অনুমতি দিন");
            return;
        }

        String endpoint = getEndpoint();
        if (endpoint.isEmpty()) {
            setStatus("অ্যাপে সার্ভারের ঠিকানা দিন");
            return;
        }

        // A selection is left alone here: commitText() replaces it when the
        // transcript arrives, and a failed transcription then loses nothing.
        try {
            audioFile = File.createTempFile("voice-keyboard-", ".m4a", getCacheDir());
            recorder = new MediaRecorder();
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            recorder.setAudioChannels(1);
            recorder.setAudioSamplingRate(RECORD_SAMPLE_RATE);
            recorder.setAudioEncodingBitRate(RECORD_BIT_RATE);
            recorder.setMaxDuration(MAX_RECORD_MS);
            recorder.setOnInfoListener((mr, what, extra) -> {
                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) {
                    mainHandler.post(() -> {
                        if (isRecording) {
                            setStatus("১২ মিনিট পূর্ণ — রেকর্ডিং পাঠানো হচ্ছে");
                            stopAndTranscribe();
                        }
                    });
                }
            });
            recorder.setOutputFile(audioFile.getAbsolutePath());
            recorder.prepare();
            recorder.start();
            isRecording = true;
            voiceSessionGeneration++;
            startVoiceTimer();
            updateMicButtonAppearance();
        } catch (Exception e) {
            cancelRecordingQuietly();
            postStatus("মাইক চালু করা যায়নি");
        }
    }

    private void stopAndTranscribe() {
        if (!isRecording) return;
        File finished = stopRecordingToFile();
        if (finished == null) {
            setStatus("রেকর্ডিং খুব ছোট");
            return;
        }
        startTranscription(finished);
    }

    /** Stops the recorder; returns the finished take, or null when nothing usable was captured. */
    private File stopRecordingToFile() {
        File file = audioFile;
        audioFile = null;
        boolean usable = true;
        try {
            if (recorder != null) recorder.stop();
        } catch (RuntimeException e) {
            // Thrown for a take that is too short, but also when the max-duration
            // limit already stopped the recorder; then the file is complete.
            usable = file != null && file.length() >= MIN_AUDIO_BYTES;
        }
        stopRecorderQuietly();
        if (!usable || file == null || !file.exists() || file.length() == 0) {
            if (file != null) file.delete();
            return null;
        }
        return file;
    }

    private void cancelRecordingQuietly() {
        voiceSessionGeneration++;
        stopRecorderQuietly();
        if (audioFile != null) {
            audioFile.delete();
            audioFile = null;
        }
    }

    private void startTranscription(File file) {
        final int session = voiceSessionGeneration;
        final String language = layoutLang;
        isTranscribing = true;
        updateMicButtonAppearance();
        executor.execute(() -> transcribe(file, session, language));
    }

    private void retryPendingAudio() {
        File file = pendingAudioFile;
        pendingAudioFile = null;
        if (file == null || !file.exists() || file.length() == 0) {
            updateMicButtonAppearance();
            return;
        }
        voiceSessionGeneration++;
        startTranscription(file);
    }

    private void insertPendingTranscript() {
        String text = pendingTranscript;
        pendingTranscript = null;
        if (text != null) commitText(text);
        updateMicButtonAppearance();
    }

    private void discardPendingVoice() {
        pendingTranscript = null;
        if (pendingAudioFile != null) {
            pendingAudioFile.delete();
            pendingAudioFile = null;
        }
        setStatus("বাদ দেওয়া হলো");
        updateMicButtonAppearance();
    }

    /** Parks a take at a fixed path so a mic tap (even after a restart) can send it again. */
    private void keepAsPendingAudio(File file) {
        File target = new File(getFilesDir(), PENDING_AUDIO_NAME);
        if (!file.equals(target)) {
            target.delete();
            if (!file.renameTo(target)) target = file;
        }
        pendingAudioFile = target;
        updateMicButtonAppearance();
    }

    private void transcribe(File file, int session, String language) {
        try {
            if (file == null || !file.exists() || file.length() == 0) {
                finishTranscribe(session, file, null, "রেকর্ডিং পাওয়া যায়নি", false);
                return;
            }
            if (file.length() > MAX_UPLOAD_AUDIO_BYTES) {
                finishTranscribe(session, file, null, "রেকর্ডিং অনেক লম্বা, পাঠানো যায়নি", false);
                return;
            }

            // The endpoint only serves signed-in users; keep the take until they are.
            String token = SupabaseAuth.validAccessToken(this);
            if (token == null) {
                finishTranscribe(session, file, null, SupabaseAuth.isSignedIn(this)
                    ? "লগইন যাচাই হয়নি — মাইকে চাপলে আবার চেষ্টা"
                    : "কিবোর্ড সেটিংসে (⚙) লগইন করুন, তারপর মাইকে চাপুন", true);
                return;
            }

            String endpoint = getEndpoint() + "/api/transcribe";
            String audio = encodeFile(file);
            JSONObject body = new JSONObject();
            body.put("audio", audio);
            body.put("mimeType", MIME_TYPE);
            body.put("language", language);

            HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(20000);
            // A 12-minute take can keep Gemini busy for a while.
            connection.setReadTimeout(240000);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("Authorization", "Bearer " + token);
            connection.setDoOutput(true);

            byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream out = connection.getOutputStream()) {
                out.write(payload);
            }

            int code = connection.getResponseCode();
            StringBuilder response = new StringBuilder();
            java.io.InputStream stream = code >= 200 && code < 300
                ? connection.getInputStream() : connection.getErrorStream();
            if (stream != null) {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) response.append(line);
                }
            }

            if (code == 401) {
                finishTranscribe(session, file, null, "লগইনের মেয়াদ শেষ — কিবোর্ড সেটিংসে (⚙) আবার লগইন করুন", true);
                return;
            }
            if (code < 200 || code >= 300) {
                // Anything but a malformed or oversized request may work on a retry.
                boolean retryable = code != 400 && code != 413;
                finishTranscribe(session, file, null,
                    retryable ? "লেখা যায়নি (" + code + ") — মাইকে চাপলে আবার চেষ্টা" : "লেখা যায়নি (" + code + ")",
                    retryable);
                return;
            }

            String text = new JSONObject(response.toString()).optString("text", "").trim();
            if (text.isEmpty()) {
                finishTranscribe(session, file, null, "কোনো কথা শোনা যায়নি", false);
                return;
            }

            finishTranscribe(session, file, text, null, false);
        } catch (Exception e) {
            // Network down, timeout, DNS…: keep the take for a retry.
            finishTranscribe(session, file, null, "নেট সমস্যা — মাইকে চাপলে আবার চেষ্টা", true);
        }
    }

    /**
     * Back on the main thread. The take is deleted only once its text is safely in
     * a field (or held as a pending transcript); a retryable failure parks it.
     */
    private void finishTranscribe(int session, File file, String text, String error, boolean retryable) {
        mainHandler.post(() -> {
            isTranscribing = false;
            if (text != null && !text.isEmpty()) {
                clearVoiceNotice();
                String finalText = VoicePunctuation.apply(text);
                if (session == voiceSessionGeneration && getCurrentInputConnection() != null) {
                    commitText(finalText);
                } else {
                    // The field the user dictated into is gone; don't type into whatever
                    // has focus now. Hold the text until the mic is tapped.
                    pendingTranscript = finalText;
                    setStatus("লেখা তৈরি — মাইকে চাপলে বসবে");
                }
                if (file != null) file.delete();
            } else {
                if (retryable && file != null && file.exists()) {
                    keepAsPendingAudio(file);
                } else if (file != null) {
                    file.delete();
                }
                if (error != null) {
                    setStatus(error);
                    showVoiceNotice(error, !SupabaseAuth.isSignedIn(this)
                        || error.contains("লগইন করুন"));
                }
            }
            updateMicButtonAppearance();
        });
    }

    private String encodeFile(File file) throws Exception {
        byte[] data = new byte[(int) file.length()];
        try (FileInputStream input = new FileInputStream(file)) {
            int offset = 0;
            while (offset < data.length) {
                int read = input.read(data, offset, data.length - offset);
                if (read < 0) break;
                offset += read;
            }
        }
        return Base64.encodeToString(data, Base64.NO_WRAP);
    }

    private void stopRecorderQuietly() {
        isRecording = false;
        stopVoiceTimer();
        if (recorder != null) {
            try {
                recorder.release();
            } catch (Exception ignored) {
            }
        }
        recorder = null;
        updateMicButtonAppearance();
    }

    private void commitText(String text) {
        lastKeyWasSpace = false;
        if (presetComposeActive && presetComposeInput != null) {
            int start = presetComposeInput.getSelectionStart();
            int end = presetComposeInput.getSelectionEnd();
            if (start < 0) start = presetComposeInput.getText().length();
            if (end < 0) end = start;
            presetComposeInput.getText().replace(Math.min(start, end), Math.max(start, end), text);
            syncBnRow1WithText();
            return;
        }
        InputConnection inputConnection = getCurrentInputConnection();
        if (inputConnection != null) {
            // Typing during live: commit the composing span first, otherwise
            // commitText() replaces the entire live text with this key.
            if (isLiveActive && !liveLastText.isEmpty()) {
                finishLiveComposingAtCaret(inputConnection);
            }
            inputConnection.commitText(text, 1);
            if (isLiveActive) {
                reanchorLiveAtCaretNow(inputConnection);
            }
        }
        onTextContextChanged();
    }

    private void deleteBackward() {
        if (presetComposeActive && presetComposeInput != null) {
            int start = presetComposeInput.getSelectionStart();
            int end = presetComposeInput.getSelectionEnd();
            if (start < 0 || end < 0) return;
            if (start != end) {
                presetComposeInput.getText().delete(Math.min(start, end), Math.max(start, end));
            } else if (start > 0) {
                presetComposeInput.getText().delete(start - 1, start);
            }
            return;
        }
        InputConnection inputConnection = getCurrentInputConnection();
        if (inputConnection == null) return;

        // Live composing: commit at the visual caret first, then delete — otherwise
        // getTextBeforeCursor reports the span start and the caret jumps backward.
        if (isLiveActive && !liveLastText.isEmpty()) {
            finishLiveComposingAtCaret(inputConnection);
        }

        CharSequence selectedText = inputConnection.getSelectedText(0);
        if (selectedText != null && selectedText.length() > 0) {
            inputConnection.commitText("", 1);
            reanchorLiveAtCaretNow(inputConnection);
            return;
        }

        inputConnection.deleteSurroundingText(1, 0);
        reanchorLiveAtCaretNow(inputConnection);
    }

    private void deleteWordBackward() {
        if (presetComposeActive && presetComposeInput != null) {
            int start = presetComposeInput.getSelectionStart();
            int end = presetComposeInput.getSelectionEnd();
            if (start < 0) start = presetComposeInput.getText().length();
            if (end < 0) end = start;
            if (start != end) {
                presetComposeInput.getText().delete(Math.min(start, end), Math.max(start, end));
                return;
            }
            String text = presetComposeInput.getText().toString();
            int i = start;
            while (i > 0 && Character.isWhitespace(text.charAt(i - 1))) i--;
            while (i > 0 && !Character.isWhitespace(text.charAt(i - 1))) i--;
            if (i == start) i = Math.max(0, start - 1);
            presetComposeInput.getText().delete(i, start);
            return;
        }
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;

        if (isLiveActive && !liveLastText.isEmpty()) {
            finishLiveComposingAtCaret(ic);
        }

        CharSequence selected = ic.getSelectedText(0);
        if (selected != null && selected.length() > 0) {
            ic.commitText("", 1);
            reanchorLiveAtCaretNow(ic);
            return;
        }

        CharSequence before = ic.getTextBeforeCursor(64, 0);
        if (before == null || before.length() == 0) {
            deleteBackward();
            return;
        }
        int len = before.length();
        int i = len;
        while (i > 0 && Character.isWhitespace(before.charAt(i - 1))) i--;
        while (i > 0 && !Character.isWhitespace(before.charAt(i - 1))) i--;
        int count = len - i;
        if (count <= 0) count = 1;
        ic.deleteSurroundingText(count, 0);
        reanchorLiveAtCaretNow(ic);
    }

    private void performSingleDelete(View source) {
        performKeyHaptic(source);
        deleteBackward();
        lastKeyWasSpace = false;
        onTextContextChanged();
    }

    private void performWordDelete(View source) {
        performKeyHaptic(source);
        deleteWordBackward();
        lastKeyWasSpace = false;
        onTextContextChanged();
    }

    private void selectAllText() {
        InputConnection inputConnection = getCurrentInputConnection();
        if (inputConnection != null) inputConnection.performContextMenuAction(android.R.id.selectAll);
    }

    private void undoText() {
        InputConnection inputConnection = getCurrentInputConnection();
        if (inputConnection != null) inputConnection.performContextMenuAction(android.R.id.undo);
    }

    private void configureDeleteButton(View button) {
        final float[] downX = {0f};
        final float[] downY = {0f};
        final boolean[] moved = {false};
        final boolean[] wordDeleted = {false};
        final boolean[] longFired = {false};
        final Runnable longPress = new Runnable() {
            @Override
            public void run() {
                if (moved[0] || wordDeleted[0]) return;
                longFired[0] = true;
                mainHandler.removeCallbacks(repeatDeleteRunnable);
                performSingleDelete(button);
                mainHandler.postDelayed(repeatDeleteRunnable, 260);
            }
        };
        button.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX[0] = event.getRawX();
                    downY[0] = event.getRawY();
                    moved[0] = false;
                    wordDeleted[0] = false;
                    longFired[0] = false;
                    activeDeleteSource = button;
                    v.setPressed(true);
                    mainHandler.postDelayed(longPress, 350);
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    float dx = event.getRawX() - downX[0];
                    float dy = event.getRawY() - downY[0];
                    if (!wordDeleted[0] && dx < -dp(40) && Math.abs(dy) < dp(28)) {
                        mainHandler.removeCallbacks(longPress);
                        mainHandler.removeCallbacks(repeatDeleteRunnable);
                        moved[0] = true;
                        wordDeleted[0] = true;
                        performWordDelete(button);
                        downX[0] = event.getRawX();
                    } else if (Math.abs(dx) > dp(12) || Math.abs(dy) > dp(12)) {
                        moved[0] = true;
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                    v.setPressed(false);
                    mainHandler.removeCallbacks(longPress);
                    mainHandler.removeCallbacks(repeatDeleteRunnable);
                    if (!moved[0] && !wordDeleted[0] && !longFired[0]) {
                        performSingleDelete(button);
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    v.setPressed(false);
                    mainHandler.removeCallbacks(longPress);
                    mainHandler.removeCallbacks(repeatDeleteRunnable);
                    return true;
                default:
                    return false;
            }
        });
    }

    private String[] longPressAltsFor(String key) {
        if (key == null || key.isEmpty()) return null;
        String[] alts = AR_LONG_PRESS.get(key);
        if (alts != null) return alts;
        alts = BN_LONG_PRESS.get(key);
        if (alts != null) return alts;
        return EN_LONG_PRESS.get(key.toLowerCase(java.util.Locale.US));
    }

    private void attachLongPressAlt(Button button, String[] alts) {
        if (alts == null || alts.length == 0) return;
        button.setOnLongClickListener(v -> {
            finalizePendingComposing();
            showSymbolPopup(button, alts);
            return true;
        });
    }

    // ---------- Smart Enter ----------

    private void performSmartEnter() {
        if (presetComposeActive) {
            commitText("\n");
            return;
        }
        finalizePendingComposing();
        if (currentEditorInfo != null) {
            boolean noEnter = (currentEditorInfo.imeOptions & EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0;
            int action = currentEditorInfo.imeOptions & EditorInfo.IME_MASK_ACTION;
            if (!noEnter && action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
                InputConnection ic = getCurrentInputConnection();
                if (ic != null) {
                    ic.performEditorAction(action);
                    return;
                }
            }
        }
        commitText("\n");
    }

    // ---------- Layout: tray, top bar, panel ----------

    private View buildInputView() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(themeBg);
        root.setPadding(dp(4), dp(6), dp(4), dp(10));
        root.addView(buildVoiceNotice(), matchWidthWrap());
        root.addView(buildTopBar(), matchWidthWrap());
        keyboardPanel = buildKeyboardPanel();
        root.addView(keyboardPanel, matchWidthWrap());
        applyVoiceOnlyVisibility();
        applyNavigationBarPadding(root);
        return root;
    }

    private View buildVoiceNotice() {
        voiceNotice = new LinearLayout(this);
        voiceNotice.setOrientation(LinearLayout.HORIZONTAL);
        voiceNotice.setGravity(Gravity.CENTER_VERTICAL);
        voiceNotice.setPadding(dp(12), dp(6), dp(8), dp(6));
        voiceNotice.setMinimumHeight(dp(52));
        voiceNotice.setBackground(roundRect(themeLiveIdleBg, dp(16)));
        voiceNotice.setVisibility(View.GONE);
        voiceNotice.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        voiceNoticeText = new TextView(this);
        voiceNoticeText.setTextSize(13);
        voiceNoticeText.setTextColor(themeKeyText);
        voiceNotice.addView(voiceNoticeText, new LinearLayout.LayoutParams(0,
            LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        voiceNoticeAction = new TextView(this);
        voiceNoticeAction.setGravity(Gravity.CENTER);
        voiceNoticeAction.setTextSize(14);
        voiceNoticeAction.setTypeface(Typeface.DEFAULT_BOLD);
        voiceNoticeAction.setTextColor(themeOnAccent);
        voiceNoticeAction.setBackground(roundRect(themeAccent, dp(14)));
        voiceNoticeAction.setPadding(dp(10), 0, dp(10), 0);
        voiceNoticeAction.setFocusable(true);
        voiceNotice.addView(voiceNoticeAction, new LinearLayout.LayoutParams(dp(88), dp(44)));
        voiceNoticeAction.setOnClickListener(v -> {
            if (voiceNoticeNeedsLogin) openKeyboardSettings(true);
            else { clearVoiceNotice(); toggleVoiceInput(); }
        });
        return voiceNotice;
    }

    private void showVoiceNotice(String message, boolean needsLogin) {
        if (voiceNotice == null) return;
        voiceNoticeNeedsLogin = needsLogin;
        voiceNoticeText.setText(message);
        voiceNoticeAction.setText(needsLogin ? "লগইন" : "আবার চেষ্টা");
        voiceNoticeAction.setContentDescription(needsLogin ? "অ্যাকাউন্টে লগইন খুলুন" : "ভয়েস আবার চালু করুন");
        voiceNotice.setVisibility(View.VISIBLE);
    }

    private void clearVoiceNotice() {
        voiceNoticeNeedsLogin = false;
        if (voiceNotice != null) voiceNotice.setVisibility(View.GONE);
    }

    private void applyNavigationBarPadding(View root) {
        final int basePadBottom = root.getPaddingBottom();
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            int bottom;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                bottom = bars.bottom;
            } else {
                bottom = insets.getSystemWindowInsetBottom();
            }
            view.setPadding(view.getPaddingLeft(), view.getPaddingTop(), view.getPaddingRight(),
                basePadBottom + bottom);
            return insets;
        });
        root.requestApplyInsets();
    }

    /**
     * One 42dp row that changes with the situation: tools or word suggestions next
     * to the mic, the recording bar, a busy note, a kept recording, or the numpad tag.
     */
    private LinearLayout buildTopBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(2), 0, dp(2), dp(6));
        topBar = bar;

        FrameLayout host = new FrameLayout(this);
        bar.addView(host, new LinearLayout.LayoutParams(0, dp(TOP_BAR_HEIGHT), 1f));
        host.addView(buildToolsGroup(), matchParentSquare());
        host.addView(buildSuggestGroup(), matchParentSquare());
        host.addView(buildRecordGroup(), matchParentSquare());
        host.addView(buildStatusPill(), matchParentSquare());
        host.addView(buildPendingGroup(), matchParentSquare());
        host.addView(buildNumpadTag(), matchParentSquare());

        micPill = buildMicPill();
        LinearLayout.LayoutParams micLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, dp(38));
        micLp.setMarginStart(dp(6));
        bar.addView(micPill, micLp);
        refreshTopBar();
        return bar;
    }

    private LinearLayout buildToolsGroup() {
        LinearLayout group = hRow();
        toolsGroup = group;
        group.addView(toolIcon(R.drawable.lc_undo, "আগের অবস্থায় ফেরান", this::undoText), toolLp(42));
        toolAll = toolText("সব", "সব লেখা সিলেক্ট করুন", this::selectAllText);
        group.addView(toolAll, toolLp(40));
        toolKar = toolText("কা", "কার-সারি দেখান বা লুকান", this::toggleBnVowelRow);
        group.addView(toolKar, toolLp(40));
        toolPresets = toolIcon(R.drawable.lc_message, "প্রিসেট মেসেজ", () -> togglePresetPopup(topBar));
        group.addView(toolPresets, toolLp(42));
        toolSettings = toolIcon(R.drawable.lc_sliders, "কিবোর্ড সেটিংস", this::openKeyboardSettings);
        group.addView(toolSettings, toolLp(42));
        toolCollapse = toolIcon(R.drawable.lc_chevron_down, "শুধু মাইক রাখুন", this::toggleKeyboardPanel);
        group.addView(toolCollapse, toolLp(42));
        toolDelete = new FrameLayout(this);
        toolDelete.setBackground(keyDrawable(themeKeyBg));
        toolDelete.setContentDescription("মুছুন");
        toolDelete.addView(tintedIcon(R.drawable.lc_backspace, themeFnText, 21), centeredLp(22));
        configureDeleteButton(toolDelete);
        LinearLayout.LayoutParams delLp = toolLp(44);
        delLp.setMarginStart(dp(4));
        group.addView(toolDelete, delLp);
        return group;
    }

    private LinearLayout buildSuggestGroup() {
        LinearLayout group = hRow();
        suggestGroup = group;
        ImageButton tools = toolIcon(R.drawable.lc_tools, "টুলবার দেখান", () -> {
            toolsRequested = true;
            refreshTopBar();
        });
        tools.setBackground(roundRect(withAlpha(themeKeyBg, 0xA6), dp(11)));
        LinearLayout.LayoutParams toolsLp = toolLp(40);
        toolsLp.height = dp(38);
        toolsLp.setMarginEnd(dp(2));
        group.addView(tools, toolsLp);
        for (int i = 0; i < suggestionViews.length; i++) {
            if (i > 0) {
                View divider = new View(this);
                divider.setBackgroundColor(withAlpha(themeKeyText, 0x24));
                group.addView(divider, new LinearLayout.LayoutParams(dp(1), dp(18)));
            }
            TextView tv = new TextView(this);
            tv.setTextSize(17);
            tv.setTextColor(themeKeyText);
            tv.setGravity(Gravity.CENTER);
            tv.setSingleLine(true);
            tv.setEllipsize(TextUtils.TruncateAt.END);
            tv.setPadding(dp(4), 0, dp(4), 0);
            if (i == SUGGESTION_SLOT_ORDER[0]) {
                tv.setTypeface(null, Typeface.BOLD);
                tv.setBackground(roundRect(withAlpha(themeKeyBg, 0xA6), dp(10)));
            }
            tv.setOnClickListener(v -> {
                CharSequence word = ((TextView) v).getText();
                if (word.length() > 0) {
                    performKeyHaptic(v);
                    applySuggestion(word.toString());
                }
            });
            group.addView(tv, new LinearLayout.LayoutParams(0, dp(38), 1f));
            suggestionViews[i] = tv;
        }
        return group;
    }

    private LinearLayout buildRecordGroup() {
        LinearLayout group = hRow();
        recordGroup = group;

        recordCancel = new FrameLayout(this);
        recordCancel.setBackground(keyDrawable(themeKeyBg, dp(19)));
        recordCancel.setContentDescription("রেকর্ডিং বাতিল");
        recordCancel.addView(tintedIcon(R.drawable.lc_x, themeMuted, 18), centeredLp(18));
        recordCancel.setOnClickListener(v -> {
            performKeyHaptic(v);
            cancelRecordingByUser();
        });
        LinearLayout.LayoutParams cancelLp = new LinearLayout.LayoutParams(dp(40), dp(40));
        cancelLp.setMarginEnd(dp(6));
        group.addView(recordCancel, cancelLp);

        LinearLayout wave = hRow();
        wave.setBackground(roundRect(themeRecTint, dp(20)));
        wave.setPadding(dp(14), 0, dp(14), 0);
        wave.setContentDescription("রেকর্ড চলছে");
        recordDot = new View(this);
        recordDot.setBackground(roundRect(themeRec, dp(5)));
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(9), dp(9));
        dotLp.setMarginEnd(dp(10));
        wave.addView(recordDot, dotLp);
        waveView = new LevelWaveView(this);
        waveView.setBarColor(themeRec);
        wave.addView(waveView, new LinearLayout.LayoutParams(0, dp(24), 1f));
        recordTimer = new TextView(this);
        recordTimer.setTextSize(14);
        recordTimer.setTypeface(Typeface.DEFAULT_BOLD);
        recordTimer.setTextColor(themeRecText);
        LinearLayout.LayoutParams timerLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        timerLp.setMarginStart(dp(10));
        wave.addView(recordTimer, timerLp);
        group.addView(wave, new LinearLayout.LayoutParams(0, dp(40), 1f));

        LinearLayout done = hRow();
        done.setBackground(pillDrawable(themeAccent));
        done.setPadding(dp(12), 0, dp(15), 0);
        recordDoneIcon = tintedIcon(R.drawable.lc_check, themeOnAccent, 18);
        done.addView(recordDoneIcon, iconLp(18, 6));
        recordDoneLabel = new TextView(this);
        recordDoneLabel.setTextSize(15);
        recordDoneLabel.setTypeface(Typeface.DEFAULT_BOLD);
        recordDoneLabel.setTextColor(themeOnAccent);
        done.addView(recordDoneLabel);
        done.setOnClickListener(v -> {
            performKeyHaptic(v);
            toggleVoiceInput();
        });
        LinearLayout.LayoutParams doneLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, dp(40));
        doneLp.setMarginStart(dp(6));
        group.addView(done, doneLp);
        return group;
    }

    private TextView buildStatusPill() {
        TextView pill = new TextView(this);
        statusPill = pill;
        pill.setGravity(Gravity.CENTER);
        pill.setTextSize(15);
        pill.setTypeface(Typeface.DEFAULT_BOLD);
        pill.setTextColor(themeBusyText);
        pill.setBackground(roundRect(themeBusyTint, dp(20)));
        // While Live is still connecting, a tap gives up instead of waiting.
        pill.setOnClickListener(v -> {
            if (isLiveConnecting) stopLive();
        });
        return pill;
    }

    private LinearLayout buildPendingGroup() {
        LinearLayout group = hRow();
        pendingGroup = group;
        LinearLayout pill = hRow();
        pill.setBackground(roundRect(themePendingTint, dp(20)));
        pill.setPadding(dp(14), 0, dp(14), 0);
        pendingIcon = tintedIcon(R.drawable.lc_refresh, themePendingText, 17);
        pill.addView(pendingIcon, iconLp(17, 8));
        pendingLabel = new TextView(this);
        pendingLabel.setTextSize(15);
        pendingLabel.setTypeface(Typeface.DEFAULT_BOLD);
        pendingLabel.setTextColor(themePendingText);
        pill.addView(pendingLabel);
        pill.setOnClickListener(v -> {
            performKeyHaptic(v);
            toggleVoiceInput();
        });
        group.addView(pill, new LinearLayout.LayoutParams(0, dp(38), 1f));
        FrameLayout discard = new FrameLayout(this);
        discard.setBackground(keyDrawable(themeKeyBg, dp(19)));
        discard.setContentDescription("রেকর্ডিং বাদ দিন");
        discard.addView(tintedIcon(R.drawable.lc_x, themeMuted, 17), centeredLp(17));
        discard.setOnClickListener(v -> {
            performKeyHaptic(v);
            discardPendingVoice();
        });
        LinearLayout.LayoutParams discardLp = new LinearLayout.LayoutParams(dp(38), dp(38));
        discardLp.setMarginStart(dp(6));
        group.addView(discard, discardLp);
        return group;
    }

    private LinearLayout buildNumpadTag() {
        LinearLayout group = hRow();
        numpadGroup = group;
        group.setPadding(dp(6), 0, dp(4), 0);
        View dot = new View(this);
        dot.setBackground(roundRect(themeAccent, dp(4)));
        group.addView(dot, iconLp(7, 8));
        TextView tag = new TextView(this);
        numpadTag = tag;
        tag.setTextSize(13);
        tag.setTextColor(themeMuted);
        group.addView(tag, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        return group;
    }

    private LinearLayout buildMicPill() {
        LinearLayout pill = hRow();
        pill.setPadding(dp(13), 0, dp(15), 0);
        micLiveDot = new View(this);
        micLiveDot.setBackground(roundRect(themeRec, dp(4)));
        pill.addView(micLiveDot, iconLp(7, 7));
        micPillIcon = new ImageView(this);
        pill.addView(micPillIcon, iconLp(18, 7));
        micPillLabel = new TextView(this);
        micPillLabel.setTextSize(15);
        micPillLabel.setTypeface(Typeface.DEFAULT_BOLD);
        pill.addView(micPillLabel);
        pill.setOnClickListener(v -> {
            performKeyHaptic(v);
            toggleVoiceInput();
        });
        pill.setOnLongClickListener(v -> {
            if (isRecording || isLiveActive || isTranscribing) return false;
            finalizePendingComposing();
            performKeyHaptic(v);
            toggleVoiceInputMode();
            return true;
        });
        return pill;
    }

    /** Shows the one top-bar state that fits right now. */
    private void refreshTopBar() {
        if (topBar == null) return;
        boolean liveMode = MainActivity.MODE_LIVE.equals(voiceInputMode);
        boolean numpad = showsNumpad() && !voiceOnlyMode;
        View show;
        boolean mic = false;
        if (isRecording || (isLiveActive && !isLiveConnecting)) {
            show = recordGroup;
            recordCancel.setVisibility(isRecording ? View.VISIBLE : View.GONE);
            recordDoneLabel.setText(isRecording ? "পাঠান" : "থামান");
            recordDoneIcon.setImageResource(isRecording ? R.drawable.lc_check : R.drawable.lc_stop);
            recordTimer.setText(formatVoiceElapsed());
        } else if (isTranscribing || isLiveConnecting) {
            show = statusPill;
            statusPill.setText(isTranscribing ? "লেখা তৈরি হচ্ছে…" : "লাইভ চালু হচ্ছে…");
        } else if (pendingTranscript != null) {
            show = pendingGroup;
            pendingLabel.setText("লেখা বসান");
            pendingIcon.setImageResource(R.drawable.lc_insert);
        } else if (pendingAudioFile != null) {
            show = pendingGroup;
            pendingLabel.setText("আবার পাঠান");
            pendingIcon.setImageResource(R.drawable.lc_refresh);
        } else if (numpad) {
            show = numpadGroup;
            numpadTag.setText(numericField ? "নম্বরের ঘর — ইংরেজি অঙ্ক" : "শুধু অঙ্ক");
        } else if (suggestionsShown && !toolsRequested && !voiceOnlyMode) {
            show = suggestGroup;
            mic = true;
        } else {
            show = toolsGroup;
            mic = true;
        }
        for (View v : new View[]{toolsGroup, suggestGroup, recordGroup, statusPill, pendingGroup, numpadGroup}) {
            v.setVisibility(v == show ? View.VISIBLE : View.GONE);
        }
        if (show == toolsGroup) {
            toolKar.setVisibility(bnDynamicRow1Enabled() && !voiceOnlyMode ? View.VISIBLE : View.GONE);
            toolSettings.setVisibility(voiceOnlyMode ? View.GONE : View.VISIBLE);
            toolDelete.setVisibility(voiceOnlyMode ? View.VISIBLE : View.GONE);
            toolCollapse.setImageResource(voiceOnlyMode ? R.drawable.lc_chevron_up : R.drawable.lc_chevron_down);
            toolCollapse.setContentDescription(voiceOnlyMode ? "পুরো কিবোর্ড দেখান" : "শুধু মাইক রাখুন");
            updateKarToggleHighlight();
        }

        micPill.setVisibility(mic ? View.VISIBLE : View.GONE);
        if (mic) {
            micPill.setAlpha(passwordField ? 0.4f : 1f);
            if (liveMode) {
                GradientDrawable bg = roundRect(themeLiveIdleBg, dp(19));
                bg.setStroke(dp(1.5f), themeAccent);
                micPill.setBackground(bg);
                micLiveDot.setVisibility(View.VISIBLE);
                micPillIcon.setVisibility(View.GONE);
                micPillLabel.setText("লাইভ");
                micPillLabel.setTextColor(themeAccentStrong);
                micPill.setContentDescription("লাইভ লেখা শুরু করুন। ধরে রাখলে রেকর্ড মোড।");
            } else {
                micPill.setBackground(pillDrawable(themeAccent));
                micLiveDot.setVisibility(View.GONE);
                micPillIcon.setVisibility(View.VISIBLE);
                micPillIcon.setImageResource(R.drawable.lc_mic);
                micPillIcon.setColorFilter(themeOnAccent, PorterDuff.Mode.SRC_IN);
                micPillLabel.setText("বলুন");
                micPillLabel.setTextColor(themeOnAccent);
                micPill.setContentDescription("ভয়েস দিয়ে লিখুন। ধরে রাখলে লাইভ মোড।");
            }
        }
    }

    /** Kept for the many call sites that just mean "voice state changed". */
    private void updateMicButtonAppearance() {
        refreshTopBar();
    }

    private void openKeyboardSettings() {
        openKeyboardSettings(false);
    }

    private void openKeyboardSettings(boolean showAccount) {
        try {
            Intent intent = new Intent(this, MainActivity.class);
            // Separate settings task: Back closes settings and returns to the app you were typing in.
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                | Intent.FLAG_ACTIVITY_NO_USER_ACTION);
            intent.putExtra(MainActivity.EXTRA_FROM_IME, true);
            intent.putExtra(MainActivity.EXTRA_SHOW_ACCOUNT, showAccount);
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "সেটিংস খোলা যায়নি", Toast.LENGTH_SHORT).show();
        }
    }

    private LinearLayout buildKeyboardPanel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);

        presetComposeBar = buildPresetComposeBar();
        presetComposeBar.setVisibility(View.GONE);
        panel.addView(presetComposeBar, matchWidthWrap());

        letterContainer = new LinearLayout(this);
        letterContainer.setOrientation(LinearLayout.VERTICAL);
        panel.addView(letterContainer, matchWidthWrap());

        bottomRowView = buildBottomRow();
        panel.addView(bottomRowView, matchWidthWrap());
        rebuildLetterRows();
        refreshBottomRowForLanguage();
        return panel;
    }

    private String spaceLanguageLabel(String lang) {
        if (MainActivity.LANG_EN.equals(lang)) return "English";
        if (MainActivity.LANG_AR.equals(lang)) return "العربية";
        return "বাংলা";
    }

    /** Next ({@code +1}) or previous ({@code -1}) enabled language. */
    private void cycleLayoutLanguage(int direction) {
        if (enabledLangs == null || enabledLangs.size() <= 1) return;
        finalizePendingComposing();
        int idx = Math.max(0, enabledLangs.indexOf(layoutLang));
        int n = enabledLangs.size();
        setLayoutLanguage(enabledLangs.get(((idx + direction) % n + n) % n));
    }

    private LinearLayout buildBottomRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        row.setPadding(0, dp(GAP_DP), 0, 0);

        moreKey = fnTextKey("", 13);
        moreKey.setOnClickListener(v -> {
            performKeyHaptic(v);
            toggleMorePage();
        });
        // Hold for the digits-only pad, in any field.
        moreKey.setOnLongClickListener(v -> {
            performKeyHaptic(v);
            openNumpad();
            return true;
        });
        row.addView(moreKey, weighted(bottomKeyHeightDp, 1.3f));

        Button comma = fnTextKey(",", 20);
        comma.setOnClickListener(v -> {
            performKeyHaptic(v);
            finalizePendingComposing();
            commitText(", ");
        });
        row.addView(comma, weighted(bottomKeyHeightDp, 0.8f));

        FrameLayout globe = new FrameLayout(this);
        globe.setBackground(keyDrawable(themeFnBg));
        globe.setContentDescription("ভাষা বদলান");
        globe.addView(tintedIcon(R.drawable.lc_globe, themeFnText, 19), centeredLp(19));
        globe.setOnClickListener(v -> {
            performKeyHaptic(v);
            showGlobeMenu(v);
        });
        row.addView(globe, weighted(bottomKeyHeightDp, 0.9f));

        spaceKeyButton = new Button(this);
        spaceKeyButton.setAllCaps(false);
        spaceKeyButton.setTextSize(13);
        spaceKeyButton.setMinWidth(0);
        spaceKeyButton.setMinHeight(0);
        spaceKeyButton.setPadding(0, 0, 0, 0);
        spaceKeyButton.setBackground(keyDrawable(themeKeyBg));
        spaceKeyButton.setTextColor(themeMuted);
        spaceKeyButton.setStateListAnimator(null);
        configureSpaceKey(spaceKeyButton);
        row.addView(spaceKeyButton, weighted(bottomKeyHeightDp, 4.3f));

        rightPunctButton = fnTextKey("।", 20);
        rightPunctButton.setOnClickListener(v -> {
            performKeyHaptic(v);
            finalizePendingComposing();
            commitText(MainActivity.LANG_BN.equals(layoutLang) ? "।" : ".");
        });
        rightPunctButton.setOnLongClickListener(v -> {
            performKeyHaptic(v);
            showSymbolPopup(v, bottomPunctAltsFor(layoutLang));
            return true;
        });
        rightPunctHint = new TextView(this);
        row.addView(withCornerHint(rightPunctButton, "?", rightPunctHint), weighted(bottomKeyHeightDp, 0.8f));

        enterKey = new FrameLayout(this);
        enterKey.setBackground(keyDrawable(themeAccent));
        enterIcon = tintedIcon(R.drawable.lc_enter, themeOnAccent, 20);
        enterKey.addView(enterIcon, centeredLp(20));
        enterLabel = new TextView(this);
        enterLabel.setTextSize(14);
        enterLabel.setTypeface(Typeface.DEFAULT_BOLD);
        enterLabel.setTextColor(themeOnAccent);
        enterLabel.setGravity(Gravity.CENTER);
        enterKey.addView(enterLabel, matchParentSquare());
        enterKey.setOnClickListener(v -> {
            performKeyHaptic(v);
            performSmartEnter();
        });
        row.addView(enterKey, weighted(bottomKeyHeightDp, 1.5f));
        refreshEnterKeyLabel();
        return row;
    }

    /**
     * Space: tap types a space (double tap: full stop), a sideways swipe switches
     * language — right for the next, left for the previous — and a long-press does too.
     */
    private void configureSpaceKey(Button space) {
        final float[] startX = {0f};
        final float[] startY = {0f};
        final boolean[] swiped = {false};
        space.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    startX[0] = event.getRawX();
                    startY[0] = event.getRawY();
                    swiped[0] = false;
                    return false;
                case MotionEvent.ACTION_MOVE: {
                    float dx = event.getRawX() - startX[0];
                    float dy = event.getRawY() - startY[0];
                    if (!swiped[0] && Math.abs(dx) >= dp(36) && Math.abs(dx) > Math.abs(dy) * 1.5f) {
                        swiped[0] = true;
                        // This listener eats the rest of the gesture, so the Button never
                        // sees UP: cancel its long-press (which also switches language).
                        v.cancelLongPress();
                        v.setPressed(false);
                        performKeyHaptic(v);
                        cycleLayoutLanguage(dx > 0 ? 1 : -1);
                    }
                    return swiped[0];
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    boolean consumed = swiped[0];
                    swiped[0] = false;
                    if (consumed) {
                        v.setPressed(false);
                        v.cancelLongPress();
                    }
                    return consumed;
                }
                default:
                    return false;
            }
        });
        space.setOnClickListener(v -> handleSpaceTap(v));
        space.setOnLongClickListener(v -> {
            performKeyHaptic(v);
            cycleLayoutLanguage(1);
            return true;
        });
    }

    private void refreshBottomRowForLanguage() {
        boolean dari = MainActivity.LANG_BN.equals(layoutLang);
        if (rightPunctButton != null) rightPunctButton.setText(dari ? "।" : ".");
        if (rightPunctHint != null) {
            rightPunctHint.setText(MainActivity.LANG_AR.equals(layoutLang) ? "؟" : "?");
        }
        if (spaceKeyButton != null) {
            // The arrows hint that a sideways swipe switches language.
            String label = enabledLangs != null && enabledLangs.size() > 1
                ? "‹  " + spaceLanguageLabel(layoutLang) + "  ›"
                : spaceLanguageLabel(layoutLang);
            spaceKeyButton.setText(label);
        }
        updateMoreKeyLabel();
        updateKarToggleVisibility();
    }

    private void updateMoreKeyLabel() {
        if (moreKey == null) return;
        String label;
        if (MainActivity.LANG_EN.equals(layoutLang)) {
            label = morePage ? "ABC" : "123";
        } else if (MainActivity.LANG_AR.equals(layoutLang)) {
            label = morePage ? "أبت" : "١٢٣";
        } else {
            label = morePage ? "অআক" : "১২৩";
        }
        moreKey.setText(label);
    }

    // ---------- Number pad (number / phone / date fields) ----------

    private void buildNumpad() {
        String[][] rows = {
            {"1", "2", "3", "-"},
            {"4", "5", "6", "+"},
            {"7", "8", "9", TOK_DELETE},
            // Bottom-left goes back to letters, where the thumb expects the page key.
            {TOK_LETTERS, "0", ".", TOK_ENTER},
        };
        for (String[] keys : rows) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(0, 0, 0, dp(GAP_DP));
            for (int i = 0; i < keys.length; i++) {
                String key = keys[i];
                float weight = i == 3 ? 0.8f : 1f;
                int height = bottomKeyHeightDp + 8;
                if (TOK_DELETE.equals(key)) {
                    row.addView(createDeleteKeyView(), weighted(height, weight));
                } else if (TOK_LETTERS.equals(key)) {
                    Button letters = fnTextKey("অ আ ক", 16);
                    letters.setTypeface(Typeface.DEFAULT_BOLD);
                    letters.setContentDescription("অক্ষরের কিবোর্ডে ফিরুন");
                    letters.setOnClickListener(v -> {
                        performKeyHaptic(v);
                        toggleMorePage();
                    });
                    row.addView(letters, weighted(height, weight));
                } else if (TOK_SPACE.equals(key)) {
                    FrameLayout space = new FrameLayout(this);
                    space.setBackground(keyDrawable(themeFnBg));
                    space.setContentDescription("স্পেস");
                    space.addView(tintedIcon(R.drawable.lc_space, themeFnText, 24), centeredLp(24));
                    space.setOnClickListener(v -> {
                        performKeyHaptic(v);
                        commitText(" ");
                    });
                    row.addView(space, weighted(height, weight));
                } else if (TOK_ENTER.equals(key)) {
                    TextView enter = new TextView(this);
                    String label = smartEnterLabel();
                    enter.setText(label != null ? label : "↵");
                    enter.setTextSize(label != null ? 15 : 20);
                    enter.setTypeface(Typeface.DEFAULT_BOLD);
                    enter.setTextColor(themeOnAccent);
                    enter.setGravity(Gravity.CENTER);
                    enter.setBackground(keyDrawable(themeAccent));
                    enter.setOnClickListener(v -> {
                        performKeyHaptic(v);
                        performSmartEnter();
                    });
                    row.addView(enter, weighted(height, weight));
                } else {
                    boolean digit = Character.isDigit(key.charAt(0));
                    Button b = digit ? letterKey(key, 24) : fnTextKey(key, 22);
                    b.setOnClickListener(v -> {
                        performKeyHaptic(v);
                        commitText(key);
                    });
                    String[] alts = "0".equals(key) ? new String[]{"+"}
                        : ".".equals(key) ? new String[]{",", "*", "#", "/", "(", ")", " "} : null;
                    if (alts != null) attachLongPressAlt(b, alts);
                    row.addView(b, weighted(height, weight));
                }
            }
            letterContainer.addView(row, matchWidthWrap());
        }
    }

    // ---------- Presets ----------

    private LinearLayout buildPresetComposeBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(0, 0, 0, dp(GAP_DP));

        presetComposeInput = new EditText(this);
        presetComposeInput.setHint("নতুন মেসেজ লিখুন…");
        presetComposeInput.setTextSize(15);
        presetComposeInput.setSingleLine(false);
        presetComposeInput.setMaxLines(3);
        presetComposeInput.setMinHeight(dp(40));
        presetComposeInput.setPadding(dp(12), dp(8), dp(12), dp(8));
        GradientDrawable inputBg = roundRect(themeSurface, dp(12));
        inputBg.setStroke(dp(1.5f), themeAccent);
        presetComposeInput.setBackground(inputBg);
        presetComposeInput.setTextColor(themeKeyText);
        presetComposeInput.setHintTextColor(themeHint);
        LinearLayout.LayoutParams inputLp = new LinearLayout.LayoutParams(0,
            LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        inputLp.setMarginEnd(dp(5));
        bar.addView(presetComposeInput, inputLp);

        TextView save = new TextView(this);
        save.setText("সেভ");
        save.setTextSize(15);
        save.setTypeface(Typeface.DEFAULT_BOLD);
        save.setTextColor(themeOnAccent);
        save.setGravity(Gravity.CENTER);
        save.setPadding(dp(14), 0, dp(14), 0);
        save.setBackground(keyDrawable(themeAccent));
        save.setOnClickListener(v -> savePresetCompose());
        bar.addView(save, composeButtonLp());

        presetComposeDelete = new FrameLayout(this);
        presetComposeDelete.setBackground(keyDrawable(themeFnBg));
        presetComposeDelete.setContentDescription("এই মেসেজ মুছুন");
        presetComposeDelete.addView(tintedIcon(R.drawable.lc_backspace, themeFnText, 18), centeredLp(18));
        presetComposeDelete.setOnClickListener(v -> deleteEditedPreset());
        bar.addView(presetComposeDelete, composeIconLp());

        FrameLayout cancel = new FrameLayout(this);
        cancel.setBackground(keyDrawable(themeFnBg));
        cancel.setContentDescription("বাতিল");
        cancel.addView(tintedIcon(R.drawable.lc_x, themeFnText, 18), centeredLp(18));
        cancel.setOnClickListener(v -> hidePresetCompose());
        bar.addView(cancel, composeIconLp());
        return bar;
    }

    private LinearLayout.LayoutParams composeButtonLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, dp(40));
        lp.setMarginStart(dp(5));
        return lp;
    }

    private LinearLayout.LayoutParams composeIconLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(40), dp(40));
        lp.setMarginStart(dp(5));
        return lp;
    }

    /** Opens the compose row; {@code editIndex} >= 0 edits that preset, -1 adds one. */
    private void showPresetCompose(int editIndex, String text) {
        finalizePendingComposing();
        if (voiceOnlyMode) {
            voiceOnlyMode = false;
            applyVoiceOnlyVisibility();
            savePreferences();
        }
        if (presetComposeBar == null) return;
        presetEditIndex = editIndex;
        presetComposeBar.setVisibility(View.VISIBLE);
        presetComposeDelete.setVisibility(editIndex >= 0 ? View.VISIBLE : View.GONE);
        presetComposeActive = true;
        presetComposeInput.setText(text != null ? text : "");
        presetComposeInput.setSelection(presetComposeInput.getText().length());
        presetComposeInput.requestFocus();
        onTextContextChanged();
    }

    private void hidePresetCompose() {
        presetComposeActive = false;
        presetEditIndex = -1;
        if (presetComposeBar != null) {
            presetComposeBar.setVisibility(View.GONE);
        }
        if (presetComposeInput != null) {
            presetComposeInput.setText("");
        }
        onTextContextChanged();
    }

    private void savePresetCompose() {
        if (presetComposeInput == null) return;
        String text = presetComposeInput.getText().toString().trim();
        if (text.isEmpty()) return;
        java.util.List<String> updated = loadPresets();
        if (presetEditIndex >= 0 && presetEditIndex < updated.size()) {
            updated.set(presetEditIndex, text);
        } else {
            if (updated.size() >= MAX_PRESETS) {
                setStatus("সর্বোচ্চ " + toBnDigits(String.valueOf(MAX_PRESETS)) + "টা মেসেজ রাখা যায়");
                return;
            }
            updated.add(text);
        }
        savePresets(updated);
        hidePresetCompose();
        setStatus("সেভ হয়েছে");
    }

    private void deleteEditedPreset() {
        java.util.List<String> updated = loadPresets();
        if (presetEditIndex >= 0 && presetEditIndex < updated.size()) {
            updated.remove(presetEditIndex);
            savePresets(updated);
            setStatus("মুছে ফেলা হয়েছে");
        }
        hidePresetCompose();
    }

    private void dismissPresetPopup() {
        if (presetPopup != null && presetPopup.isShowing()) {
            presetPopup.dismiss();
        }
        presetPopup = null;
        if (toolPresets != null) toolPresets.setBackground(null);
    }

    private void togglePresetPopup(View anchor) {
        if (presetPopup != null && presetPopup.isShowing()) {
            dismissPresetPopup();
            return;
        }
        finalizePendingComposing();
        dismissGlobePopup();
        dismissSymbolPopup();
        hidePresetCompose();
        showPresetPopup(anchor);
    }

    private void showPresetPopup(View anchor) {
        java.util.List<String> presets = loadPresets();
        LinearLayout card = popupCard(dp(12), dp(20));
        int width = Math.max(dp(260), anchor.getWidth() - dp(16));

        LinearLayout head = hRow();
        head.setPadding(dp(6), dp(2), dp(6), dp(8));
        TextView title = new TextView(this);
        title.setText("প্রিসেট মেসেজ");
        title.setTextSize(15);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(themeKeyText);
        head.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView count = new TextView(this);
        count.setText(toBnDigits(presets.size() + " / " + MAX_PRESETS));
        count.setTextSize(12);
        count.setTextColor(themeHint);
        head.addView(count);
        card.addView(head, matchWidthWrap());

        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        if (presets.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("এখনো কোনো মেসেজ নেই। বারবার লেখেন এমন কথা এখানে রেখে দিন।");
            empty.setTextSize(14);
            empty.setTextColor(themeMuted);
            empty.setPadding(dp(8), dp(4), dp(8), dp(10));
            list.addView(empty, matchWidthWrap());
        }
        for (int i = 0; i < presets.size(); i++) {
            final int index = i;
            final String message = presets.get(i);
            LinearLayout item = hRow();
            item.setBackground(roundRect(themeSurfaceAlt, dp(12)));
            TextView say = new TextView(this);
            say.setText(message);
            say.setTextSize(16);
            say.setTextColor(themeKeyText);
            say.setSingleLine(true);
            say.setEllipsize(TextUtils.TruncateAt.END);
            say.setGravity(Gravity.CENTER_VERTICAL);
            say.setPadding(dp(14), 0, dp(6), 0);
            say.setOnClickListener(v -> {
                performKeyHaptic(v);
                finalizePendingComposing();
                commitText(message);
                dismissPresetPopup();
            });
            item.addView(say, new LinearLayout.LayoutParams(0, dp(46), 1f));
            ImageButton edit = toolIcon(R.drawable.lc_pencil, "এডিট বা মুছুন", () -> {
                dismissPresetPopup();
                showPresetCompose(index, message);
            });
            item.addView(edit, new LinearLayout.LayoutParams(dp(42), dp(42)));
            LinearLayout.LayoutParams lp = matchWidthWrap();
            lp.bottomMargin = dp(4);
            list.addView(item, lp);
        }
        ScrollView scroll = new ScrollView(this);
        scroll.addView(list, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT));
        card.addView(scroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
            presets.isEmpty() ? LinearLayout.LayoutParams.WRAP_CONTENT
                : dp(Math.min(5, presets.size()) * 50)));

        LinearLayout add = hRow();
        add.setGravity(Gravity.CENTER);
        add.setBackground(roundRect(themeAccentTint, dp(12)));
        add.addView(tintedIcon(R.drawable.lc_plus, themeAccentStrong, 18), iconLp(18, 8));
        TextView addLabel = new TextView(this);
        addLabel.setText("নতুন মেসেজ যোগ করুন");
        addLabel.setTextSize(15);
        addLabel.setTypeface(Typeface.DEFAULT_BOLD);
        addLabel.setTextColor(themeAccentStrong);
        add.addView(addLabel);
        add.setOnClickListener(v -> {
            dismissPresetPopup();
            showPresetCompose(-1, null);
        });
        LinearLayout.LayoutParams addLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(46));
        addLp.topMargin = dp(6);
        card.addView(add, addLp);

        presetPopup = showAbove(card, anchor, width, dp(8));
        if (toolPresets != null) toolPresets.setBackground(roundRect(themeAccentTint, dp(12)));
    }

    // ---------- Menus ----------

    private void showGlobeMenu(View anchor) {
        finalizePendingComposing();
        dismissPresetPopup();
        dismissSymbolPopup();
        dismissGlobePopup();
        LinearLayout card = popupCard(dp(8), dp(16));
        for (String lang : enabledLangs) {
            boolean current = layoutLang.equals(lang);
            LinearLayout item = hRow();
            item.setPadding(dp(14), 0, dp(10), 0);
            if (current) item.setBackground(roundRect(themeAccentTint, dp(10)));
            TextView name = new TextView(this);
            name.setText(spaceLanguageLabel(lang));
            name.setTextSize(16);
            name.setTextColor(current ? themeAccentStrong : themeKeyText);
            if (current) name.setTypeface(Typeface.DEFAULT_BOLD);
            item.addView(name, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            if (current) item.addView(tintedIcon(R.drawable.lc_check, themeAccentStrong, 18), iconLp(18, 0));
            item.setOnClickListener(v -> {
                setLayoutLanguage(lang);
                dismissGlobePopup();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(44));
            lp.bottomMargin = dp(2);
            card.addView(item, lp);
        }
        View divider = new View(this);
        divider.setBackgroundColor(withAlpha(themeKeyText, 0x1A));
        LinearLayout.LayoutParams divLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(1));
        divLp.setMargins(dp(6), dp(4), dp(6), dp(4));
        card.addView(divider, divLp);
        TextView other = new TextView(this);
        other.setText("অন্য কিবোর্ড…");
        other.setTextSize(15);
        other.setTextColor(themeMuted);
        other.setGravity(Gravity.CENTER_VERTICAL);
        other.setPadding(dp(14), 0, dp(14), 0);
        other.setOnClickListener(v -> {
            dismissGlobePopup();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                switchToNextInputMethod(false);
            } else {
                android.view.inputmethod.InputMethodManager manager =
                    (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                if (manager != null) manager.showInputMethodPicker();
            }
        });
        card.addView(other, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44)));
        globePopup = showAbove(card, anchor, dp(216), 0);
    }

    private void dismissGlobePopup() {
        if (globePopup != null && globePopup.isShowing()) {
            globePopup.dismiss();
        }
        globePopup = null;
    }

    private void dismissSymbolPopup() {
        if (symbolPopup != null && symbolPopup.isShowing()) {
            symbolPopup.dismiss();
        }
        symbolPopup = null;
    }

    private void showSymbolPopup(View anchor, String[] symbols) {
        dismissSymbolPopup();
        dismissGlobePopup();
        dismissPresetPopup();
        int cols = Math.min(5, symbols.length);
        LinearLayout card = popupCard(dp(6), dp(14));
        for (int i = 0; i < symbols.length; i += cols) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (int j = i; j < Math.min(i + cols, symbols.length); j++) {
                String sym = symbols[j];
                Button btn = letterKey(sym, 18);
                btn.setOnClickListener(v -> {
                    performKeyHaptic(v);
                    finalizePendingComposing();
                    commitText(sym);
                    dismissSymbolPopup();
                });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(42), dp(44));
                lp.setMargins(dp(2), dp(2), dp(2), dp(2));
                row.addView(btn, lp);
            }
            card.addView(row, matchWidthWrap());
        }
        symbolPopup = showAbove(card, anchor, dp(12 + cols * 46), dp(6));
    }

    /** A rounded, shadowed popup card in the theme's surface colour. */
    private LinearLayout popupCard(int padding, int radius) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(roundRect(themeSurface, radius));
        card.setPadding(padding, padding, padding, padding);
        return card;
    }

    /**
     * Shows {@code content} just above {@code anchor}. Not focusable: a focusable popup
     * steals window focus, which makes web views drop the text field and close the keyboard.
     */
    private PopupWindow showAbove(View content, View anchor, int width, int xOffset) {
        content.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        PopupWindow popup = new PopupWindow(content, width, LinearLayout.LayoutParams.WRAP_CONTENT, false);
        popup.setElevation(dp(12));
        popup.setBackgroundDrawable(null);
        popup.setOutsideTouchable(true);
        popup.showAsDropDown(anchor, xOffset, -(anchor.getHeight() + content.getMeasuredHeight() + dp(6)));
        return popup;
    }

    // ---------- Keys and drawables ----------

    /** A letter key: theme key colour, raised by a 1dp bottom edge. */
    private Button letterKey(String label, float textSp) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(textSp);
        button.setMinWidth(0);
        button.setMinHeight(0);
        button.setPadding(0, 0, 0, 0);
        button.setStateListAnimator(null);
        button.setBackground(keyDrawable(themeKeyBg));
        button.setTextColor(themeKeyText);
        return button;
    }

    /** A function key (comma, 123, shift…): the darker function-key colour. */
    private Button fnTextKey(String label, float textSp) {
        Button button = letterKey(label, textSp);
        button.setBackground(keyDrawable(themeFnBg));
        button.setTextColor(themeFnText);
        return button;
    }

    private FrameLayout createDeleteKeyView() {
        FrameLayout wrap = new FrameLayout(this);
        wrap.setBackground(keyDrawable(themeFnBg));
        wrap.setContentDescription("মুছুন");
        wrap.addView(tintedIcon(R.drawable.lc_backspace, themeFnText, 21), centeredLp(22));
        configureDeleteButton(wrap);
        return wrap;
    }

    /** Wraps a key so the first long-press alternative shows in its top corner. */
    private View withCornerHint(Button button, String hint, TextView hintView) {
        FrameLayout wrap = new FrameLayout(this);
        wrap.addView(button, matchParentSquare());
        TextView tip = hintView != null ? hintView : new TextView(this);
        tip.setText(hint);
        tip.setTextSize(9);
        tip.setTypeface(Typeface.DEFAULT_BOLD);
        tip.setTextColor(themeHint);
        tip.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.TOP | Gravity.END;
        lp.topMargin = dp(2);
        lp.setMarginEnd(dp(4));
        wrap.addView(tip, lp);
        return wrap;
    }

    private Drawable keyDrawable(int fill) {
        return keyDrawable(fill, dp(KEY_RADIUS_DP));
    }

    /** Rounded key with a 1dp darker bottom edge; pressed state shifts the fill. */
    private Drawable keyDrawable(int fill, int radius) {
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_pressed}, raisedKey(pressedColor(fill), radius));
        states.addState(new int[]{}, raisedKey(fill, radius));
        return states;
    }

    private Drawable raisedKey(int fill, int radius) {
        LayerDrawable layers = new LayerDrawable(new Drawable[]{
            roundRect(themeShadow, radius), roundRect(fill, radius)});
        layers.setLayerInset(1, 0, 0, 0, dp(1));
        return layers;
    }

    private Drawable pillDrawable(int fill) {
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_pressed}, roundRect(pressedColor(fill), dp(999)));
        states.addState(new int[]{}, roundRect(fill, dp(999)));
        return states;
    }

    private GradientDrawable roundRect(int color, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        return d;
    }

    /** Darkens light colours and lightens dark ones, so the press always shows. */
    private static int pressedColor(int color) {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        hsv[2] = hsv[2] > 0.5f ? hsv[2] * 0.86f : Math.min(1f, hsv[2] + 0.12f);
        return Color.HSVToColor(Color.alpha(color), hsv);
    }

    private static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (alpha << 24);
    }

    private LinearLayout hRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        return row;
    }

    private ImageView tintedIcon(int res, int color, int sizeDp) {
        ImageView icon = new ImageView(this);
        icon.setImageResource(res);
        icon.setColorFilter(color, PorterDuff.Mode.SRC_IN);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return icon;
    }

    private ImageButton toolIcon(int res, String description, Runnable action) {
        ImageButton button = new ImageButton(this);
        button.setImageResource(res);
        button.setColorFilter(themeMuted, PorterDuff.Mode.SRC_IN);
        button.setBackground(null);
        button.setScaleType(ImageView.ScaleType.CENTER);
        button.setContentDescription(description);
        button.setOnClickListener(v -> {
            performKeyHaptic(v);
            action.run();
        });
        return button;
    }

    private TextView toolText(String label, String description, Runnable action) {
        TextView button = new TextView(this);
        button.setText(label);
        button.setTextSize(15);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setTextColor(themeFnText);
        button.setGravity(Gravity.CENTER);
        button.setContentDescription(description);
        button.setOnClickListener(v -> {
            performKeyHaptic(v);
            action.run();
        });
        return button;
    }

    private LinearLayout.LayoutParams toolLp(int widthDp) {
        return new LinearLayout.LayoutParams(dp(widthDp), dp(TOP_BAR_HEIGHT));
    }

    private FrameLayout.LayoutParams centeredLp(int sizeDp) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(sizeDp), dp(sizeDp));
        lp.gravity = Gravity.CENTER;
        return lp;
    }

    private LinearLayout.LayoutParams iconLp(int sizeDp, int marginEndDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp));
        lp.setMarginEnd(dp(marginEndDp));
        return lp;
    }

    private static String toBnDigits(String s) {
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            out.append(c >= '0' && c <= '9' ? (char) ('০' + (c - '0')) : c);
        }
        return out.toString();
    }

    private void setLayoutLanguage(String lang) {
        finalizePendingComposing();
        layoutLang = lang;
        morePage = false;
        resetBnKarState();
        rebuildLetterRows();
        savePreferences();
        onTextContextChanged();
    }

    private void toggleKeyboardPanel() {
        finalizePendingComposing();
        voiceOnlyMode = !voiceOnlyMode;
        applyVoiceOnlyVisibility();
        savePreferences();
    }

    private void applyVoiceOnlyVisibility() {
        if (keyboardPanel != null) {
            keyboardPanel.setVisibility(voiceOnlyMode ? View.GONE : View.VISIBLE);
        }
        if (voiceOnlyMode) {
            dismissSymbolPopup();
            dismissPresetPopup();
            hidePresetCompose();
            morePage = false;
            updateMoreKeyLabel();
        }
        refreshTopBar();
    }

    private void cancelRecordingByUser() {
        if (!isRecording) return;
        cancelRecordingQuietly();
        setStatus("রেকর্ডিং বাতিল");
        refreshTopBar();
    }

    private void rebuildLetterRows() {
        if (letterContainer == null) return;
        dismissSymbolPopup();
        letterContainer.removeAllViews();
        bnRow1Layout = null;
        qwertyLetterButtons.clear();
        shiftKeyView = null;
        shiftState = 0;
        boolean numpad = showsNumpad();
        if (bottomRowView != null) bottomRowView.setVisibility(numpad ? View.GONE : View.VISIBLE);
        if (numpad) {
            buildNumpad();
        } else {
            boolean qwerty = isRomanQwertyMode() && !morePage;
            String[][] rows = morePage ? moreRowsFor(layoutLang) : layoutRowsFor(layoutLang);
            for (int i = 0; i < rows.length; i++) {
                if (!morePage && i == 0 && bnDynamicRow1Enabled()) {
                    bnRow1Layout = buildDynamicKeyRow(row1Keys(), true, false);
                    letterContainer.addView(bnRow1Layout, matchWidthWrap());
                } else {
                    addTextRow(letterContainer, rows[i], false, qwerty);
                }
            }
        }
        refreshShiftVisuals();
        refreshBottomRowForLanguage();
        syncBnRow1WithText();
        refreshTopBar();
    }

    private boolean showsNumpad() {
        return morePage && (numericField || numpadRequested);
    }

    private void openNumpad() {
        dismissSymbolPopup();
        dismissPresetPopup();
        hidePresetCompose();
        finalizePendingComposing();
        numpadRequested = true;
        morePage = true;
        resetBnKarState();
        rebuildLetterRows();
    }

    private void toggleMorePage() {
        dismissSymbolPopup();
        dismissPresetPopup();
        hidePresetCompose();
        finalizePendingComposing();
        morePage = !morePage;
        if (!morePage) numpadRequested = false;
        resetBnKarState();
        rebuildLetterRows();
    }

    private void updateKarToggleVisibility() {
        refreshTopBar();
    }

    private void updateKarToggleHighlight() {
        if (toolKar == null) return;
        boolean on = bnDynamicRow1Enabled() && !bnRow1Vowels;
        toolKar.setBackground(on ? roundRect(themeAccentTint, dp(11)) : null);
        toolKar.setTextColor(on ? themeAccentStrong : themeFnText);
    }

    private void refreshSuggestions() {
        BanglaDictionary dict = bnDictionary;
        boolean available = dict != null && MainActivity.LANG_BN.equals(layoutLang)
            && !presetComposeActive && !passwordField;
        java.util.List<String> words = available
            ? dict.suggest(currentBnPartialWord(), suggestionViews.length)
            : java.util.Collections.<String>emptyList();
        for (int i = 0; i < suggestionViews.length; i++) {
            TextView slot = suggestionViews[SUGGESTION_SLOT_ORDER[i]];
            if (slot != null) slot.setText(i < words.size() ? words.get(i) : "");
        }
        suggestionsShown = !words.isEmpty();
        refreshTopBar();
    }

    private Button buildShiftKeyView() {
        Button b = fnTextKey(shiftState == 2 ? "⇪" : "⇧", 18);
        b.setContentDescription("শিফট");
        b.setOnClickListener(v -> {
            performKeyHaptic(v);
            cycleShift();
        });
        shiftKeyView = b;
        return b;
    }

    private void refreshShiftVisuals() {
        if (shiftKeyView != null) {
            shiftKeyView.setText(shiftState == 2 ? "⇪" : "⇧");
            boolean on = shiftState != 0;
            shiftKeyView.setBackground(keyDrawable(on ? themeAccentTint : themeFnBg));
            shiftKeyView.setTextColor(on ? themeAccentStrong : themeFnText);
        }
        for (Button b : qwertyLetterButtons) {
            Object tag = b.getTag();
            if (tag instanceof String) {
                String base = (String) tag;
                b.setText(shiftActive() ? base.toUpperCase(java.util.Locale.US) : base);
            }
        }
    }

    private LinearLayout buildDynamicKeyRow(String[] keys, boolean isRow1, boolean qwerty) {
        LinearLayout keyRow = new LinearLayout(this);
        keyRow.setOrientation(LinearLayout.HORIZONTAL);
        keyRow.setGravity(Gravity.CENTER);
        keyRow.setPadding(0, 0, 0, dp(GAP_DP));
        populateKeyRow(keyRow, keys, isRow1, qwerty);
        return keyRow;
    }

    private void populateKeyRow(LinearLayout keyRow, String[] keys, boolean isRow1, boolean qwerty) {
        boolean karRow = isRow1 && bnDynamicRow1Enabled() && !bnRow1Vowels;
        for (String key : keys) {
            if (TOK_DELETE.equals(key)) {
                keyRow.addView(createDeleteKeyView(), weighted(letterKeyHeightDp, 1));
                continue;
            }
            if (TOK_BACK.equals(key)) {
                // Legacy sentinel — page toggle lives on the bottom-left more key.
                continue;
            }
            if (TOK_SHIFT.equals(key)) {
                keyRow.addView(buildShiftKeyView(), weighted(letterKeyHeightDp, 1f));
                continue;
            }
            String label = qwerty && shiftActive() ? key.toUpperCase(java.util.Locale.US) : key;
            Button button = letterKey(label, qwerty ? 19 : karRow ? 18 : 20);
            if (karRow) {
                button.setBackground(keyDrawable(themeKarBg));
                button.setTextColor(themeKarText);
            }

            if (qwerty) {
                button.setTag(key);
                qwertyLetterButtons.add(button);
                button.setOnClickListener(v -> {
                    performKeyHaptic(v);
                    onQwertyLetterPressed(button);
                });
            } else if (",".equals(key)) {
                button.setOnClickListener(v -> {
                    performKeyHaptic(v);
                    finalizePendingComposing();
                    commitText(", ");
                });
            } else if ("।".equals(key)) {
                button.setOnClickListener(v -> {
                    performKeyHaptic(v);
                    finalizePendingComposing();
                    commitText("।");
                });
            } else if (isRow1) {
                button.setOnClickListener(v -> {
                    performKeyHaptic(v);
                    onRow1KeyPressed(key);
                });
            } else {
                button.setOnClickListener(v -> {
                    performKeyHaptic(v);
                    onLetterKeyPressed(key);
                });
            }
            String[] alts = karRow ? null : longPressAltsFor(key);
            attachLongPressAlt(button, alts);
            View keyView = alts != null ? withCornerHint(button, alts[0], null) : button;
            keyRow.addView(keyView, weighted(letterKeyHeightDp, 1));
        }
    }

    private void addTextRow(LinearLayout root, String[] keys, boolean isRow1, boolean qwerty) {
        LinearLayout keyRow = buildDynamicKeyRow(keys, isRow1, qwerty);
        root.addView(keyRow, matchWidthWrap());
    }

    private void performKeyHaptic(View v) {
        if (v == null || !hapticEnabled) return;
        v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
    }

    // ---------- Smart Enter ----------

    private void refreshEnterKeyLabel() {
        if (enterKey == null) return;
        String label = smartEnterLabel();
        enterLabel.setText(label != null ? label : "");
        enterLabel.setVisibility(label != null ? View.VISIBLE : View.GONE);
        enterIcon.setVisibility(label != null ? View.GONE : View.VISIBLE);
        enterKey.setContentDescription(label != null ? label : "নতুন লাইন");
    }

    /** The field's action word in Bangla, or null for a plain new line (shown as an icon). */
    private String smartEnterLabel() {
        if (currentEditorInfo == null) return null;
        boolean noEnter = (currentEditorInfo.imeOptions & EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0;
        if (noEnter) return null;
        int action = currentEditorInfo.imeOptions & EditorInfo.IME_MASK_ACTION;
        switch (action) {
            case EditorInfo.IME_ACTION_DONE: return "সম্পন্ন";
            case EditorInfo.IME_ACTION_SEARCH: return "খুঁজুন";
            case EditorInfo.IME_ACTION_GO: return "যান";
            case EditorInfo.IME_ACTION_SEND: return "পাঠান";
            case EditorInfo.IME_ACTION_NEXT: return "পরবর্তী";
            default: return null;
        }
    }

    // ---------- Voice timer and level ----------

    /** Ticks while recording or live: feeds the waveform and updates the timer. */
    private final Runnable voiceTickRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isRecording && !isLiveActive) return;
            float level = 0f;
            if (isRecording && recorder != null) {
                try {
                    level = (float) Math.sqrt(Math.min(1.0, recorder.getMaxAmplitude() / 18000.0));
                } catch (RuntimeException ignored) {
                }
            } else if (liveTranscriber != null) {
                level = liveTranscriber.level();
            }
            if (waveView != null) waveView.push(level);
            if (recordTimer != null) recordTimer.setText(formatVoiceElapsed());
            mainHandler.postDelayed(this, VOICE_TICK_MS);
        }
    };

    private String formatVoiceElapsed() {
        long seconds = voiceActivityStartMs <= 0 ? 0
            : (System.currentTimeMillis() - voiceActivityStartMs) / 1000;
        return toBnDigits((seconds / 60) + ":" + String.format(java.util.Locale.US, "%02d", seconds % 60));
    }

    private void startVoiceTimer() {
        voiceActivityStartMs = System.currentTimeMillis();
        if (waveView != null) waveView.clear();
        mainHandler.removeCallbacks(voiceTickRunnable);
        mainHandler.post(voiceTickRunnable);
        startRecordDotPulse();
    }

    private void stopVoiceTimer() {
        voiceActivityStartMs = 0;
        mainHandler.removeCallbacks(voiceTickRunnable);
        if (recordDotPulse != null) {
            recordDotPulse.cancel();
            recordDotPulse = null;
        }
        if (recordDot != null) recordDot.setAlpha(1f);
    }

    private void startRecordDotPulse() {
        if (recordDot == null) return;
        if (recordDotPulse != null) recordDotPulse.cancel();
        // Follows the system animation scale, so "remove animations" stills it.
        recordDotPulse = ObjectAnimator.ofFloat(recordDot, View.ALPHA, 1f, 0.35f);
        recordDotPulse.setDuration(650);
        recordDotPulse.setRepeatCount(ValueAnimator.INFINITE);
        recordDotPulse.setRepeatMode(ValueAnimator.REVERSE);
        recordDotPulse.start();
    }

    // ---------- Theme ----------

    private void applyKeySize(String size) {
        if (MainActivity.SIZE_SMALL.equals(size)) {
            letterKeyHeightDp = 36;
            bottomKeyHeightDp = 38;
        } else if (MainActivity.SIZE_LARGE.equals(size)) {
            letterKeyHeightDp = 46;
            bottomKeyHeightDp = 48;
        } else {
            letterKeyHeightDp = 40;
            bottomKeyHeightDp = 42;
        }
    }

    /**
     * Palettes. Each sets the tray, letter keys, function keys, the kar row, one
     * accent (mic, enter) and the soft tints the recording / pending bars use.
     */
    private void applyTheme(String theme) {
        // Shared light-theme status tints.
        themeRecTint = 0xffF5E2DC; themeRec = 0xffC9543F; themeRecText = 0xff8E2F20;
        themePendingTint = 0xffFBEBCB; themePendingText = 0xff6B4600;
        if (MainActivity.THEME_DARK.equals(theme)) {
            themeBg = 0xff1C1E1B; themeKeyBg = 0xff2E312C; themeKeyText = 0xffECEDE8;
            themeFnBg = 0xff40443D; themeFnText = 0xffD5D7D0;
            themeKarBg = 0xff2B3A2E; themeKarText = 0xffB9D6BE;
            themeAccent = 0xff9DC3A3; themeOnAccent = 0xff10261A;
            themeAccentTint = 0xff2B3A2E; themeAccentStrong = 0xffB9D6BE;
            themeMuted = 0xffB4B7AF; themeHint = 0xff9A9D95; themeShadow = 0x73000000;
            themeSurface = 0xff2A2D29; themeSurfaceAlt = 0xff33362F; themeLiveIdleBg = 0xff2E312C;
            themeRecTint = 0xff4A2A24; themeRec = 0xffE07A64; themeRecText = 0xffF2B5A8;
            themePendingTint = 0xff4A3A1A; themePendingText = 0xffF2D08A;
        } else if (MainActivity.THEME_LIGHT.equals(theme)) {
            themeBg = 0xffECEFF1; themeKeyBg = 0xffFFFFFF; themeKeyText = 0xff202124;
            themeFnBg = 0xffD5DADF; themeFnText = 0xff3C4043;
            themeKarBg = 0xffE3ECF7; themeKarText = 0xff1A4F8B;
            themeAccent = 0xff1A73E8; themeOnAccent = 0xffFFFFFF;
            themeAccentTint = 0xffDCE8FB; themeAccentStrong = 0xff174EA6;
            themeMuted = 0xff5F6368; themeHint = 0xff80868B; themeShadow = 0x26202124;
            themeSurface = 0xffFFFFFF; themeSurfaceAlt = 0xffF1F3F4; themeLiveIdleBg = 0xffFFFFFF;
        } else if (MainActivity.THEME_COLORFUL.equals(theme)) {
            themeBg = 0xff15203A; themeKeyBg = 0xff243056; themeKeyText = 0xffFFFFFF;
            themeFnBg = 0xff33416B; themeFnText = 0xffDDE3F5;
            themeKarBg = 0xff3D3420; themeKarText = 0xffFFD580;
            themeAccent = 0xffFFB300; themeOnAccent = 0xff1A1300;
            themeAccentTint = 0xff3D3420; themeAccentStrong = 0xffFFD580;
            themeMuted = 0xffB9C2DD; themeHint = 0xff8F9AC0; themeShadow = 0x66000000;
            themeSurface = 0xff243056; themeSurfaceAlt = 0xff2C3963; themeLiveIdleBg = 0xff243056;
            themeRecTint = 0xff4A2A30; themeRec = 0xffFF8A7A; themeRecText = 0xffFFC2B8;
            themePendingTint = 0xff4A3A1A; themePendingText = 0xffFFD580;
        } else { // warm (default): sage on paper
            themeBg = 0xffE9E8E1; themeKeyBg = 0xffFFFFFF; themeKeyText = 0xff1E1F24;
            themeFnBg = 0xffD9D8CF; themeFnText = 0xff3B3E45;
            themeKarBg = 0xffE1ECDE; themeKarText = 0xff244A2F;
            themeAccent = 0xff3F6B4A; themeOnAccent = 0xffFFFFFF;
            themeAccentTint = 0xffDDE9DB; themeAccentStrong = 0xff2F5338;
            themeMuted = 0xff5B5E66; themeHint = 0xff7C7F86; themeShadow = 0x261E1F24;
            themeSurface = 0xffFFFFFF; themeSurfaceAlt = 0xffF4F3EE; themeLiveIdleBg = 0xffFFFFFF;
        }
        themeBusyTint = themeAccentTint;
        themeBusyText = themeAccentStrong;
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private FrameLayout.LayoutParams matchParentSquare() {
        return new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        );
    }

    private LinearLayout.LayoutParams matchWidthWrap() {
        return new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
    }

    private LinearLayout.LayoutParams weighted(int heightDp, float weight) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(heightDp), weight);
        params.setMargins(dp(2), 0, dp(2), 0);
        return params;
    }

    private void loadPreferences() {
        finalizePendingComposing();
        SharedPreferences prefs = getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE);
        MainActivity.applyComfortFix0401(prefs);
        voiceOnlyMode = prefs.getBoolean(MainActivity.KEY_VOICE_ONLY, false);
        layoutLang = prefs.getString(MainActivity.KEY_LAYOUT_LANG, MainActivity.LANG_BN);
        voiceInputMode = prefs.getString(MainActivity.KEY_VOICE_INPUT_MODE, MainActivity.MODE_RECORD);
        if (!MainActivity.MODE_RECORD.equals(voiceInputMode)
            && !MainActivity.MODE_LIVE.equals(voiceInputMode)) {
            voiceInputMode = MainActivity.MODE_RECORD;
        }
        if (!MainActivity.LANG_BN.equals(layoutLang)
            && !MainActivity.LANG_EN.equals(layoutLang)
            && !MainActivity.LANG_AR.equals(layoutLang)) {
            layoutLang = MainActivity.LANG_BN;
        }

        hapticEnabled = prefs.getBoolean(MainActivity.KEY_HAPTIC, true);
        String sizePref = prefs.getString(MainActivity.KEY_KEY_SIZE, MainActivity.DEFAULT_KEY_SIZE);
        applyKeySize(sizePref);

        String themePref = prefs.getString(MainActivity.KEY_THEME, MainActivity.DEFAULT_THEME);
        applyTheme(themePref);

        String enabledRaw = prefs.getString(MainActivity.KEY_ENABLED_LANGS, MainActivity.DEFAULT_ENABLED_LANGS);
        enabledLangs = parseEnabledLangs(enabledRaw);
        if (!enabledLangs.contains(layoutLang)) {
            layoutLang = enabledLangs.get(0);
        }
        morePage = false;
        resetBnKarState();
        shiftState = 0;
        appliedConfigSig = configSignature(
            themePref, enabledRaw, hapticEnabled, sizePref, voiceInputMode
        );
    }

    private java.util.List<String> parseEnabledLangs(String raw) {
        java.util.List<String> list = new java.util.ArrayList<>();
        if (raw != null) {
            for (String part : raw.split(",")) {
                String s = part.trim();
                boolean known = MainActivity.LANG_BN.equals(s)
                    || MainActivity.LANG_EN.equals(s)
                    || MainActivity.LANG_AR.equals(s);
                if (known && !list.contains(s)) list.add(s);
            }
        }
        if (list.isEmpty()) list.add(MainActivity.LANG_BN);
        return list;
    }

    private void savePreferences() {
        getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(MainActivity.KEY_VOICE_ONLY, voiceOnlyMode)
            .putString(MainActivity.KEY_LAYOUT_LANG, layoutLang)
            .putString(MainActivity.KEY_VOICE_INPUT_MODE, voiceInputMode)
            .apply();
    }

    /**
     * When the keyboard opens: renew a login token that is close to expiry and, in
     * Live mode, fetch the voice key, so that pressing the mic needs no extra round
     * trip (and no lucky network moment). Own thread: the retries must not hold up a
     * transcription waiting on {@code executor}. At most once a minute.
     */
    private void prefetchSonioxKeyIfNeeded() {
        if (!SupabaseAuth.isSignedIn(this)) return;
        final Context app = getApplicationContext();
        final boolean live = MainActivity.MODE_LIVE.equals(voiceInputMode);
        final String endpoint = getEndpoint();
        long now = System.currentTimeMillis();
        boolean needKey = live && SonioxKeyCache.getValidKey(app) == null;
        if (now - lastWarmUpMs < 60_000 && !needKey) return;
        lastWarmUpMs = now;
        new Thread(() -> {
            SupabaseAuth.warmUp(app);
            if (live && !endpoint.isEmpty() && SonioxKeyCache.getValidKey(app) == null) {
                try {
                    SonioxKeyCache.fetchAndCache(app, endpoint);
                } catch (Exception ignored) {
                    // Pressing the mic tries again and reports the problem then.
                }
            }
        }, "voice-warm-up").start();
    }

    private String getEndpoint() {
        SharedPreferences prefs = getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE);
        String endpoint = prefs.getString(MainActivity.KEY_ENDPOINT, MainActivity.DEFAULT_ENDPOINT);
        if (endpoint == null) return "";
        endpoint = endpoint.trim();
        while (endpoint.endsWith("/")) endpoint = endpoint.substring(0, endpoint.length() - 1);
        return endpoint;
    }

    private void postStatus(String message) {
        mainHandler.post(() -> setStatus(message));
    }

    /** Status strip removed — show important errors as toast only. */
    private void setStatus(String message) {
        if (message == null || message.isEmpty()) return;
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
