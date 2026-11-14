package com.drehverschluss.packscope.gui.model;

import com.drehverschluss.packscope.core.PackRef;
import com.drehverschluss.packscope.core.ScanResult;
import com.drehverschluss.packscope.core.ScanResult.PackSummary;
import java.util.HashMap;
import java.util.Map;

/**
 * Pack names as in the pack screen (title), shortened for tooltips and rows, with "(hidden)" for packs that have no
 * entry in the pack screen (Phase-3A-Bericht 4).
 */
public final class Names {
    /** Longest name in tooltips and rows before it is cut with "…". */
    public static final int MAX_LENGTH = 40;

    private final Map<String, String> titles = new HashMap<>();
    private final Map<String, Boolean> hidden = new HashMap<>();
    private final int maxLength;

    public Names(ScanResult result) {
        this(result, MAX_LENGTH);
    }

    public Names(ScanResult result, int maxLength) {
        this.maxLength = maxLength;
        for (PackRef p : result.order().packs()) {
            titles.put(p.id(), p.title());
            hidden.put(p.id(), p.hidden() && p.isResourcePack());
        }
        for (PackSummary s : result.packs()) {
            titles.putIfAbsent(s.id(), s.title());
            hidden.putIfAbsent(s.id(), s.hidden() && s.parentId() == null);
        }
    }

    /** Full title, or the id if unknown. */
    public String title(String id) {
        return titles.getOrDefault(id, id);
    }

    public boolean hidden(String id) {
        return hidden.getOrDefault(id, false);
    }

    /** Shortened title; a {@link Line} with "(hidden)" for hidden packs, else a {@link String}. */
    public Object display(String id) {
        String t = shorten(title(id), maxLength);
        return hidden(id) ? Line.of("packscope.name.hidden", t) : t;
    }

    public static String shorten(String text, int max) {
        if (text.length() <= max) return text;
        return text.substring(0, Math.max(1, max - 1)).stripTrailing() + "…";
    }
}
