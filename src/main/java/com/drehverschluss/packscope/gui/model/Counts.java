package com.drehverschluss.packscope.gui.model;

import java.util.ArrayList;
import java.util.List;

/** "3 files, 1 language entry, 2 sound events" as {@link Line.Parts}, numbers in the game language format. */
public final class Counts {
    private Counts() {}

    public static Line count(String what, long n, String lang) {
        return Line.of("packscope.count." + what + (n == 1 ? ".one" : ".many"), Numbers.format(n, lang));
    }

    /** Only the non-zero parts; "0 files" if all are zero. */
    public static Line.Parts parts(int files, int langKeys, int soundEvents, int filtered, String lang) {
        return parts(files, langKeys, soundEvents, filtered, 0, lang);
    }

    /** @param dead .mcmeta files without effect */
    public static Line.Parts parts(int files, int langKeys, int soundEvents, int filtered, int dead, String lang) {
        List<Object> parts = new ArrayList<>();
        if (files > 0) parts.add(count("files", files, lang));
        if (langKeys > 0) parts.add(count("lang_keys", langKeys, lang));
        if (soundEvents > 0) parts.add(count("sound_events", soundEvents, lang));
        if (filtered > 0) parts.add(count("filtered", filtered, lang));
        if (dead > 0) parts.add(count("dead", dead, lang));
        if (parts.isEmpty()) parts.add(count("files", 0, lang));
        return new Line.Parts(parts);
    }

    public static Line.Parts parts(ConflictView.Group g, String lang) {
        return parts(g.files(), g.langKeys(), g.soundEvents(), g.filtered(), g.dead(), lang);
    }
}
