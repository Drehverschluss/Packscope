package com.drehverschluss.packscope.gui.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** Live logic shared by the vanilla and the Packed Packs screen (Phase 4): newest order wins, cache, grace time. */
class LiveLogicTest {
    private long now;
    private final Map<String, ResultTracker.Cached<String>> store = new HashMap<>();
    private final ResultTracker.Cache<String> cache = new ResultTracker.Cache<>() {
        @Nullable
        @Override
        public ResultTracker.Cached<String> get(String key) {
            return store.get(key);
        }

        @Override
        public void put(String key, String result, int session) {
            store.put(key, new ResultTracker.Cached<>(result, session));
        }
    };

    private ResultTracker<String> tracker(int session) {
        return new ResultTracker<>(session, cache, () -> now);
    }

    @Test
    void onlyTheNewestOrderIsEvaluated() {
        ResultTracker<String> t = tracker(1);
        assertEquals("A", t.request("A"), "first order starts at once");
        assertNull(t.request("B"), "one evaluation at a time");
        assertNull(t.request("C"));
        assertEquals("C", t.finished("A", "result A"), "B is skipped, only the newest order follows");
        assertNull(t.drawn(), "the result of A is not shown for order C");
        assertEquals("result A", store.get("A").result(), "but it is cached");
        assertTrue(t.pending());
        assertNull(t.finished("C", "result C"));
        assertEquals("result C", t.drawn());
        assertFalse(t.pending());
        assertEquals(2, t.evaluations());
    }

    @Test
    void sameOrderAgainComesFromTheCache() {
        ResultTracker<String> t = tracker(1);
        t.finished(t.request("A"), "result A");
        t.finished(t.request("B"), "result B");
        assertNull(t.request("A"), "swap back: cached in this session, no evaluation");
        assertEquals("result A", t.drawn());
        assertEquals(2, t.evaluations());
        assertNull(t.request("A"), "unchanged order: nothing to do");
    }

    @Test
    void oldResultStaysForTheGraceTime() {
        ResultTracker<String> t = tracker(1);
        t.finished(t.request("A"), "result A");
        int gen = t.generation();
        assertEquals("B", t.request("B"));
        assertEquals("result A", t.drawn(), "no flicker right after a change");
        now += ResultTracker.GRACE_NANOS - 1;
        assertEquals("result A", t.drawn());
        now += 1;
        assertNull(t.drawn(), "then 'checking'");
        assertEquals("result A", t.latest(), "the detail screen keeps the old result while checking");
        assertTrue(t.generation() > gen);
    }

    @Test
    void resultOfAnEarlierScreenIsProvisional() {
        ResultTracker<String> first = tracker(1);
        first.finished(first.request("A"), "old A");
        ResultTracker<String> reopened = tracker(2);
        assertEquals("A", reopened.request("A"), "checked again when the screen opens (files may have changed)");
        assertEquals("old A", reopened.drawn(), "meanwhile the result of the last opening is shown");
        now += ResultTracker.PROVISIONAL_NANOS;
        assertNull(reopened.drawn(), "at most 5 s");
        reopened.finished("A", "new A");
        assertEquals("new A", reopened.drawn());
    }

    @Test
    void failedEvaluationShowsNothing() {
        ResultTracker<String> t = tracker(1);
        assertNull(t.finished(t.request("A"), null));
        assertTrue(t.failed());
        assertFalse(t.pending());
        assertNull(t.drawn());
        assertEquals("A", t.requestAgain(), "can be tried again");
    }

    @Test
    void orderWatchComparesIds() {
        OrderWatch w = new OrderWatch();
        assertFalse(w.changed(List.of()), "lists not built yet");
        assertTrue(w.changed(List.of("file/B", "file/A", "vanilla")));
        assertFalse(w.changed(List.of("file/B", "file/A", "vanilla")), "same ids: no new evaluation");
        assertTrue(w.changed(List.of("file/A", "file/B", "vanilla")), "moved");
        assertTrue(w.changed(List.of("file/A", "vanilla")), "disabled");
        assertTrue(w.due(1_000));
        assertFalse(w.due(1_000 + OrderWatch.POLL_NANOS - 1), "one check per frame");
        assertTrue(w.due(1_000 + OrderWatch.POLL_NANOS));
    }
}
