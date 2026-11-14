package com.drehverschluss.packscope.gui.model;

import com.drehverschluss.packscope.core.Locations;
import java.util.Locale;

/** Filter of the detail screen by file type (Konzept 5.2). */
public enum FileCategory {
    ALL, TEXTURES, MODELS, LANG, SOUNDS, OTHER;

    /**
     * Category of a location ({@code namespace:path}). Language keys belong to {@link #LANG}, sound events to
     * {@link #SOUNDS} (see {@link ConflictView.Item#category()}).
     */
    public static FileCategory of(String location) {
        String path = location.indexOf(':') >= 0 ? Locations.path(location) : location;
        if (path.startsWith("textures/")) return TEXTURES;
        if (path.startsWith("models/") || path.startsWith("blockstates/")) return MODELS;
        if (path.startsWith("lang/")) return LANG;
        if (path.startsWith("sounds/") || path.equals("sounds.json")) return SOUNDS;
        return OTHER;
    }

    /** Whether something of category {@code c} passes this filter. */
    public boolean accepts(FileCategory c) {
        return this == ALL || this == c;
    }

    public FileCategory next() {
        return values()[(ordinal() + 1) % values().length];
    }

    public String key() {
        return "packscope.filter." + name().toLowerCase(Locale.ROOT);
    }

    /** Texture file that the preview can show. */
    public static boolean isPreviewable(String location) {
        return of(location) == TEXTURES && location.endsWith(".png");
    }
}
