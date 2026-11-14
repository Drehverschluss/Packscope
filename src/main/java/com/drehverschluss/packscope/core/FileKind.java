package com.drehverschluss.packscope.core;

/** How the game uses a file when several packs have it (Konzept 3). */
public enum FileKind {
    /** Whole file, the top pack wins. */
    WHOLE,
    /** {@code blockstates/*.json}: merged per block state in game, rated as whole file (decision 14.11.2026). */
    BLOCKSTATE,
    /** {@code lang/<language>.json}: merged per key. */
    LANG,
    /** {@code sounds.json} directly in the namespace folder: merged per event, unless replace is true. */
    SOUNDS,
    /** {@code atlases/*.json}, {@code font/*.json}: merged, never a conflict. */
    MERGED,
    /** {@code *.mcmeta} next to a resource: the top one at or above the winning resource counts. */
    MCMETA;

    public static FileKind of(String location) {
        String path = Locations.path(location);
        if (path.endsWith(".mcmeta")) return MCMETA;
        if (path.equals("sounds.json")) return SOUNDS;
        if (path.startsWith("lang/") && path.endsWith(".json") && path.indexOf('/', 5) < 0) return LANG;
        if (path.startsWith("blockstates/") && path.endsWith(".json")) return BLOCKSTATE;
        if ((path.startsWith("atlases/") || path.startsWith("font/")) && path.endsWith(".json")) return MERGED;
        return WHOLE;
    }

    /** Language code of a {@link #LANG} file ({@code en_us}). */
    public static String language(String location) {
        String path = Locations.path(location);
        return path.substring(5, path.length() - 5);
    }

    public String id() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
