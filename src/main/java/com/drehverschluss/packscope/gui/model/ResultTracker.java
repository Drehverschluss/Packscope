package com.drehverschluss.packscope.gui.model;

import java.util.function.LongSupplier;
import org.jetbrains.annotations.Nullable;

/**
 * Which result a pack screen shows while its order changes (Konzept 5.1 "Live", Phase-3B-Bericht 3.1), shared by the
 * vanilla screen and the Packed Packs screen. Pure logic without Minecraft, so it can be unit tested:
 * <ul>
 *     <li>at most one evaluation runs; while it runs, later orders wait and only the <b>newest</b> one is evaluated
 *     afterwards (the ones in between are skipped);</li>
 *     <li>a result for an old order goes into the cache but is not shown;</li>
 *     <li>a cached result of the same screen session is shown at once; one of an earlier session (an earlier opening of
 *     the pack screen) is shown for at most {@link #PROVISIONAL_NANOS} while the order is checked again, because files
 *     may have changed;</li>
 *     <li>after a change the old result stays on screen for {@link #GRACE_NANOS} before "checking" appears (no flicker
 *     on every arrow click).</li>
 * </ul>
 * The caller computes the key of the current order and starts the evaluation whenever a method returns a key.
 *
 * @param <R> result type
 */
public final class ResultTracker<R> {
    /** After an order change the old result stays on screen this long before "checking" appears. */
    public static final long GRACE_NANOS = 300_000_000L;
    /** A result of an earlier pack screen is shown at most this long while it is checked again. */
    public static final long PROVISIONAL_NANOS = 5_000_000_000L;

    /** Result for one order key, from the pack screen session {@code session}. */
    public record Cached<R>(R result, int session) {}

    /** Results shared by all pack screens of the game session. */
    public interface Cache<R> {
        @Nullable
        Cached<R> get(String key);

        void put(String key, R result, int session);
    }

    private final int session;
    private final Cache<R> cache;
    private final LongSupplier clock;

    private String key = "";
    @Nullable
    private R shown;
    @Nullable
    private R previous;
    private long previousUntil;
    private boolean running;
    private boolean failed;
    private int generation;
    private int evaluations;

    public ResultTracker(int session, Cache<R> cache, LongSupplier clock) {
        this.session = session;
        this.cache = cache;
        this.clock = clock;
    }

    /**
     * The order of the screen has the key {@code k} now.
     *
     * @return the key to evaluate now, or null (nothing to do, cached, or an evaluation is still running)
     */
    @Nullable
    public String request(String k) {
        if (k.equals(key) && (shown != null || running)) return null;
        key = k;
        failed = false;
        Cached<R> c = cache.get(k);
        if (c != null && c.session() == session) {
            show(c.result());
            return null;
        }
        long now = clock.getAsLong();
        if (shown != null) {
            previous = shown;
            previousUntil = now + GRACE_NANOS;
        }
        if (c != null) {
            // same order in an earlier pack screen: show it while checking again
            previous = c.result();
            previousUntil = now + PROVISIONAL_NANOS;
        }
        if (shown != null) generation++;
        shown = null;
        if (running) return null;
        running = true;
        evaluations++;
        return k;
    }

    /** Evaluates the current key again even if its result is shown (after a marking changed nothing in the key). */
    @Nullable
    public String requestAgain() {
        String k = key;
        key = "";
        return request(k);
    }

    /**
     * The evaluation of {@code k} ended.
     *
     * @param result null if it failed
     * @return the key to evaluate next (the order changed meanwhile), or null
     */
    @Nullable
    public String finished(String k, @Nullable R result) {
        running = false;
        if (result != null) cache.put(k, result, session);
        if (k.equals(key)) {
            if (result != null) {
                show(result);
            } else {
                failed = true;
                previous = null;
                generation++;
            }
            return null;
        }
        // the order changed meanwhile: only the newest one counts
        return requestAgain();
    }

    private void show(R r) {
        if (shown != r) generation++;
        shown = r;
        previous = null;
    }

    /** Result to draw now: the current one, or the previous one shortly after a change. */
    @Nullable
    public R drawn() {
        if (shown != null) return shown;
        if (previous != null && clock.getAsLong() < previousUntil) return previous;
        return null;
    }

    /** The current result, or the one before while the order is evaluated again; null at first. */
    @Nullable
    public R latest() {
        return shown != null ? shown : previous;
    }

    /** The result for the current key, or null while it is evaluated. */
    @Nullable
    public R shown() {
        return shown;
    }

    public boolean pending() {
        return shown == null && !failed;
    }

    public boolean failed() {
        return failed;
    }

    public boolean running() {
        return running;
    }

    /** Changes whenever the shown result changes. */
    public int generation() {
        return generation;
    }

    /** Evaluations started (the rest came from the cache). */
    public int evaluations() {
        return evaluations;
    }

    public String key() {
        return key;
    }
}
