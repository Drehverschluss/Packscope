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

    /** English label for reports. */
    public String label() {
        return switch (this) {
            case COMPLETELY_COVERED -> "Completely covered";
            case AGAINST_MARKING -> "Against marking";
            case REPLACES_SOUNDS -> "Replaces sounds";
            case WINS_AND_LOSES -> "Wins and loses";
            case WINS -> "Wins";
            case LOSES -> "Loses";
            case INTENDED_ONLY -> "Intended only";
            case NO_CONFLICT -> "No conflict";
        };
    }

    /** Warning statuses (red/yellow in the UI). */
    public boolean isWarning() {
        return this == COMPLETELY_COVERED || this == AGAINST_MARKING || this == REPLACES_SOUNDS;
    }
}
