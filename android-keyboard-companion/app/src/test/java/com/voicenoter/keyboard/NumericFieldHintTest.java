package com.voicenoter.keyboard;

import org.junit.Test;
import static org.junit.Assert.*;

public class NumericFieldHintTest {
    private static final int TEXT = 0x1;
    private static final int TEXT_EMAIL = 0x21;
    private static final int TEXT_PASSWORD = 0x81;
    private static final int NUMBER = 0x2;

    @Test public void digitWordingOpensNumpad() {
        for (String hint : new String[]{"Enter OTP", "Mobile number", "Phone", "PIN",
                "6-digit verification code", "Amount", "মোবাইল নম্বর", "ওটিপি দিন",
                "যাচাই কোড", "টাকার পরিমাণ", "Your age"}) {
            assertTrue(hint, NumericFieldHint.looksNumeric(TEXT, hint, null));
        }
        assertTrue(NumericFieldHint.looksNumeric(TEXT_PASSWORD, "4-digit PIN", null));
    }

    @Test public void lettersWordingStaysOnLetters() {
        for (String hint : new String[]{"Shipping address", "Promo code", "Coupon code",
                "Email or phone", "Username", "Type a message", "Spinner", "ইমেইল বা মোবাইল",
                "Your name", ""}) {
            assertFalse(hint, NumericFieldHint.looksNumeric(TEXT, hint, null));
        }
    }

    @Test public void onlyPlainTextFieldsAreGuessed() {
        assertFalse(NumericFieldHint.looksNumeric(TEXT_EMAIL, "Phone", null));
        assertFalse(NumericFieldHint.looksNumeric(NUMBER, "Phone", null));
        assertFalse(NumericFieldHint.looksNumeric(TEXT, null, null));
    }

    @Test public void labelCountsToo() {
        assertTrue(NumericFieldHint.looksNumeric(TEXT, null, "OTP"));
    }
}
