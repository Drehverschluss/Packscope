package com.drehverschluss.packscope.core;

/** Status of an active resource pack, in order of importance (Konzept 4). */
public enum PackStatus {
    /** Nothing of the pack reaches the game: every item is overridden, hidden or only a copy of a higher pack. */
    COMPLETELY_COVERED,
    /** An intended pair lies the other way round (both packs get this status). */
    AGAINST_MARKING,
    /** Replaces sound events of lower resource packs with {@code "replace": true} (not intended). */
    REPLACES_SOUNDS,
    WINS_AND_LOSES,
    WINS,
    LOSES,
    /** Has conflicts, but all of them are marked as intended. */
    INTENDED_ONLY,
    NO_CONFLICT;

    public String id() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }

    /** English name for reports, the same as the status names of the UI ({@code en_us}). */
    public String label() {
        return switch (this) {
            case COMPLETELY_COVERED -> "Completely covered";
            case AGAINST_MARKING -> "Against marking";
            case REPLACES_SOUNDS -> "Replaces sounds";
            case WINS_AND_LOSES -> "Overrides and is overridden";
            case WINS -> "Overrides other packs";
            case LOSES -> "Is overridden";
            case INTENDED_ONLY -> "Only intended conflicts";
            case NO_CONFLICT -> "No conflict";
        };
    }
}
