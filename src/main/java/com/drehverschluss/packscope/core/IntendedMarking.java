package com.drehverschluss.packscope.core;

import com.google.gson.JsonObject;
import org.jetbrains.annotations.Nullable;

/**
 * "{@code winner} over {@code loser} is intended" (Konzept 4.1). Ids as written in the file (exact id, or matched by
 * base name / {@code regex:}).
 *
 * @param note  optional text for people, shown in the detail screen
 * @param extra unknown fields of the entry, written back unchanged (never null, may be empty)
 */
public record IntendedMarking(String winner, String loser, @Nullable String note, JsonObject extra) {
    public IntendedMarking {
        extra = extra == null ? new JsonObject() : extra.deepCopy();
    }

    public IntendedMarking(String winner, String loser, @Nullable String note) {
        this(winner, loser, note, new JsonObject());
    }

    /** Whether this marking says "{@code upperId} over {@code lowerId}". */
    public boolean covers(String upperId, String lowerId) {
        return PackNames.matches(winner, upperId) && PackNames.matches(loser, lowerId);
    }

    /** Whether this marking says the opposite of "{@code upperId} over {@code lowerId}". */
    public boolean reverses(String upperId, String lowerId) {
        return PackNames.matches(winner, lowerId) && PackNames.matches(loser, upperId);
    }

    @Override
    public JsonObject extra() {
        return extra.deepCopy();
    }
}
