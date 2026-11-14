package com.drehverschluss.packscope.core;

import java.util.List;

/**
 * Immutable snapshot of the intended markings, handed to one evaluation. Changes in the {@link IntendedStore} create a
 * new snapshot; a running evaluation never sees half a change.
 *
 * @param version  increases with every change (part of result caching in the UI)
 * @param warnings problems with the file (broken JSON ...), shown in the report
 */
public record IntendedSet(List<IntendedMarking> markings, long version, List<String> warnings) {
    public static final IntendedSet EMPTY = new IntendedSet(List.of(), 0, List.of());

    public IntendedSet {
        markings = List.copyOf(markings);
        warnings = List.copyOf(warnings);
    }

    public static IntendedSet of(IntendedMarking... markings) {
        return new IntendedSet(List.of(markings), 1, List.of());
    }

    /** Index of the first marking that says "{@code upperId} over {@code lowerId}", or -1. */
    public int covering(String upperId, String lowerId) {
        for (int i = 0; i < markings.size(); i++) if (markings.get(i).covers(upperId, lowerId)) return i;
        return -1;
    }

    /** Index of the first marking that says "{@code lowerId} over {@code upperId}", or -1. */
    public int reversing(String upperId, String lowerId) {
        for (int i = 0; i < markings.size(); i++) if (markings.get(i).reverses(upperId, lowerId)) return i;
        return -1;
    }
}
