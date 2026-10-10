package com.voicenoter.keyboard;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Converts spoken punctuation phrases in transcripts into real symbols.
 */
public final class VoicePunctuation {
    private VoicePunctuation() {}

    // Longer phrases come before their prefixes ("প্রশ্নবোধক চিহ্ন" before
    // "প্রশ্নবোধক"), otherwise the short rule fires first and leaves a stray word.
    // Words that are also ordinary vocabulary ("দণ্ড", "স্পেস", "period", "dash",
    // "colon") are deliberately absent: they would rewrite real sentences.
    private static final String[][] RULES = {
        // Bangla phrases
        {"পূর্ণচ্ছেদ", "।"},
        {"পূর্ণ ছেদ", "।"},
        {"ফুলস্টপ", "।"},
        {"ফুল স্টপ", "।"},
        {"ডট", "."},
        {"প্রশ্নবোধক চিহ্ন", "?"},
        {"প্রশ্ন চিহ্ন", "?"},
        {"প্রশ্নচিহ্ন", "?"},
        {"প্রশ্নবোধক", "?"},
        {"বিস্ময়সূচক চিহ্ন", "!"},
        {"বিস্ময়সূচক", "!"},
        {"কমা", ","},
        {"সেমিকোলন", ";"},
        {"কোলন", ":"},
        {"নতুন লাইন", "\n"},
        {"নিউ লাইন", "\n"},
        {"লাইন ব্রেক", "\n"},
        {"হাইফেন", "-"},
        {"ড্যাশ", "—"},
        // English phrases
        {"full stop", "."},
        {"question mark", "?"},
        {"exclamation mark", "!"},
        {"exclamation point", "!"},
        {"comma", ","},
        {"semicolon", ";"},
        {"new line", "\n"},
        {"newline", "\n"},
        {"line break", "\n"},
        {"open quote", "\""},
        {"close quote", "\""},
    };

    // A phrase only matches as a whole word. Bengali vowel signs are marks (\p{M}),
    // not letters, so they count as word characters too — otherwise "ডটে" would
    // become ".ে". Compiled once: live mode runs this on every transcript update.
    private static final Pattern[] PATTERNS = new Pattern[RULES.length];
    static {
        for (int i = 0; i < RULES.length; i++) {
            PATTERNS[i] = Pattern.compile(
                "(?iu)(?<![\\p{L}\\p{M}])" + Pattern.quote(RULES[i][0]) + "(?![\\p{L}\\p{M}])"
            );
        }
    }

    // "গুগল ডট কম" is a web address: the dot hugs both neighbours.
    private static final Pattern DOT_BETWEEN_WORDS = Pattern.compile(
        "(?<=[\\p{L}\\p{M}\\p{N}])\\s+ডট\\s+(?=[\\p{L}\\p{N}])"
    );

    public static String apply(String text) {
        if (text == null || text.isEmpty()) return text;
        String result = DOT_BETWEEN_WORDS.matcher(text).replaceAll(".");
        for (int i = 0; i < RULES.length; i++) {
            Matcher m = PATTERNS[i].matcher(result);
            if (m.find()) {
                result = m.replaceAll(Matcher.quoteReplacement(RULES[i][1]));
            }
        }
        // Tidy spaces before punctuation
        result = result.replaceAll(" +([.,!?;:।؟])", "$1");
        result = result.replaceAll(" *\\n *", "\n");
        return result;
    }

    public static String normalizeLangHint(String layoutLang) {
        if (layoutLang == null) return "";
        return layoutLang.toLowerCase(Locale.US);
    }
}
