package com.drehverschluss.packscope.core;

/** Status of an active resource pack, in order of importance (Konzept 4). */
public enum PackStatus {
    /** Every own file/key/event (without harmless duplicates) is overridden or hidden: the pack has no effect. */
    COMPLETELY_COVERED,
    /** Replaces sound events of lower resource packs with {@code "replace": true}. */
    REPLACES_SOUNDS,
    WINS_AND_LOSES,
    WINS,
    LOSES,
    NO_CONFLICT;

    public String id() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }

    /** English label for reports. */
    public String label() {
        return switch (this) {
            case COMPLETELY_COVERED -> "Completely covered";
            case REPLACES_SOUNDS -> "Replaces sounds";
            case WINS_AND_LOSES -> "Wins and loses";
            case WINS -> "Wins";
            case LOSES -> "Loses";
            case NO_CONFLICT -> "No conflict";
        };
    }
}
