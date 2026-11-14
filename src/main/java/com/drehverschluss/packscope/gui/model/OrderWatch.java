package com.drehverschluss.packscope.gui.model;

import java.util.List;

/**
 * Cheap check whether the pack ids of a pack screen changed since the last look (Packed Packs: the list of
 * {@code ScreenContext.getSelectedPacks()}, compared on every rebuild and at the latest while the icons are drawn).
 * An empty list means the screen has not built its lists yet and is ignored. {@link #due(long)} limits the checks
 * while drawing to one per frame.
 */
public final class OrderWatch {
    /** Checks while drawing are at most this often (several icons per frame share one check). */
    public static final long POLL_NANOS = 4_000_000L;

    private List<String> last = List.of();
    private long lastPoll = Long.MIN_VALUE;

    /** Stores {@code ids} and returns whether they differ from the stored ones (false for an empty list). */
    public boolean changed(List<String> ids) {
        if (ids.isEmpty() || ids.equals(last)) return false;
        last = List.copyOf(ids);
        return true;
    }

    /** Whether a check while drawing is due at {@code now} (nanoseconds); marks it as done. */
    public boolean due(long now) {
        if (lastPoll != Long.MIN_VALUE && now - lastPoll < POLL_NANOS) return false;
        lastPoll = now;
        return true;
    }

    public List<String> last() {
        return last;
    }
}
