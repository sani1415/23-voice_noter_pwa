package com.voicenoter.keyboard;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Guesses that a plain text field wants digits from its placeholder or label,
 * for apps that mark an OTP or phone box as ordinary text. A wrong guess costs one
 * tap (the numpad's letters button), so it only fires on clear wording.
 */
final class NumericFieldHint {
    private NumericFieldHint() {}

    // InputType constants, copied so this stays testable without Android.
    private static final int TYPE_MASK_CLASS = 0x0000000f;
    private static final int TYPE_MASK_VARIATION = 0x00000ff0;
    private static final int TYPE_CLASS_TEXT = 0x00000001;
    private static final int TYPE_TEXT_VARIATION_URI = 0x00000010;
    private static final int TYPE_TEXT_VARIATION_EMAIL_ADDRESS = 0x00000020;
    private static final int TYPE_TEXT_VARIATION_PERSON_NAME = 0x00000060;
    private static final int TYPE_TEXT_VARIATION_POSTAL_ADDRESS = 0x00000070;
    private static final int TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS = 0x000000d0;

    /** Whole English words, or Bangla words anywhere in the hint. */
    private static final Pattern DIGIT_WORDS = Pattern.compile(
        "\\b(otp|pin|cvv|cvc|zip|postcode|phone|mobile|cell|amount|qty|quantity|age|"
            + "verification code|security code|one[- ]time|account number|card number|"
            + "nid|national id)\\b"
            + "|ওটিপি|মোবাইল|ফোন|নম্বর|নাম্বার|পিন|কোড|টাকা|পরিমাণ|বয়স");

    /** Wording that means letters are expected after all. */
    private static final Pattern LETTER_WORDS = Pattern.compile(
        "\\b(e-?mail|username|user name|promo|coupon|referral|voucher|name|address)\\b"
            + "|ইমেইল|ইউজার|প্রোমো|কুপন|ঠিকানা|@");

    static boolean looksNumeric(int inputType, CharSequence hint, CharSequence label) {
        if ((inputType & TYPE_MASK_CLASS) != TYPE_CLASS_TEXT) return false;
        int variation = inputType & TYPE_MASK_VARIATION;
        if (variation == TYPE_TEXT_VARIATION_URI
            || variation == TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            || variation == TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS
            || variation == TYPE_TEXT_VARIATION_PERSON_NAME
            || variation == TYPE_TEXT_VARIATION_POSTAL_ADDRESS) {
            return false;
        }
        String text = ((hint != null ? hint : "") + " " + (label != null ? label : ""))
            .toLowerCase(Locale.ROOT).trim();
        if (text.isEmpty()) return false;
        return DIGIT_WORDS.matcher(text).find() && !LETTER_WORDS.matcher(text).find();
    }
}
