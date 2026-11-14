package com.drehverschluss.packscope.gui.model;

import java.text.NumberFormat;
import java.util.Locale;

/** Numbers in the format of the game language ({@code de_de} → "1.323", {@code en_us} → "1,323"). */
public final class Numbers {
    private Numbers() {}

    /** Java locale of a Minecraft language code ({@code en_us}, {@code de_de}, {@code pt_br} ...). */
    public static Locale locale(String languageCode) {
        if (languageCode == null || languageCode.isBlank()) return Locale.ROOT;
        return Locale.forLanguageTag(languageCode.replace('_', '-'));
    }

    public static String format(long value, String languageCode) {
        return NumberFormat.getIntegerInstance(locale(languageCode)).format(value);
    }
}
