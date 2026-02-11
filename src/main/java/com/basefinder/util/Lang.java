package com.basefinder.util;

/**
 * Simple bilingual translation utility (English / French).
 * All modules sync their language setting to this static field.
 * Usage: Lang.t("English text", "Texte français")
 */
public class Lang {

    private static boolean french = true;

    /**
     * Returns the appropriate translation based on the current language.
     */
    public static String t(String en, String fr) {
        return french ? fr : en;
    }

    public static boolean isFrench() {
        return french;
    }

    public static void setFrench(boolean f) {
        french = f;
    }
}
