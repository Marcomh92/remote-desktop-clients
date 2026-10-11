package com.undatech.opaque.input;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for {@link InputDownTracker}.
 * <p>
 * The tracker records RDP "down" ids and reports orphan ups. Every scenario is fully
 * deterministic and single-threaded: the class is documented as thread-confined, so no
 * concurrency is exercised here. Assertions cover only the public contract — the boolean
 * returned by {@code markUp} and the exact contents and order of the array returned by
 * {@code drain}.
 */
public class InputDownTrackerTest {

    @Test
    public void markUp_afterMatchingDown_returnsFalseAndReleasesId() {
        InputDownTracker tracker = new InputDownTracker();

        tracker.markDown(42);

        assertFalse("a UP matching a held DOWN is a normal release", tracker.markUp(42));
        assertArrayEquals(new int[0], tracker.drain()); // 42 is no longer held
    }

    @Test
    public void markUp_withoutMatchingDown_returnsTrue() {
        InputDownTracker tracker = new InputDownTracker();

        assertTrue("an UP with no matching DOWN is an orphan", tracker.markUp(42));
        assertArrayEquals(new int[0], tracker.drain());
    }

    @Test
    public void markDown_repeated_keepsExactlyOneHeldEntry() {
        InputDownTracker tracker = new InputDownTracker();

        tracker.markDown(7);
        tracker.markDown(7);
        tracker.markDown(7);

        assertArrayEquals(new int[] {7}, tracker.drain()); // the repeats collapse to one id
    }

    @Test
    public void markUp_afterAutoRepeat_returnsFalseThenTrueOnEmpty() {
        InputDownTracker tracker = new InputDownTracker();

        tracker.markDown(7);
        tracker.markDown(7); // key auto-repeat re-sends DOWN with no intervening UP

        assertFalse("the first UP releases the single held id", tracker.markUp(7));
        assertTrue("a second UP now has nothing left to release", tracker.markUp(7));
    }

    @Test
    public void markDown_afterRelease_reholdsId() {
        InputDownTracker tracker = new InputDownTracker();

        tracker.markDown(7);
        assertFalse(tracker.markUp(7));

        tracker.markDown(7); // re-press of the same id

        assertFalse("the id is held again, so this UP is a normal release", tracker.markUp(7));
    }

    @Test
    public void distinctIds_areTrackedIndependently() {
        InputDownTracker tracker = new InputDownTracker();

        tracker.markDown(0);
        tracker.markDown(1);
        tracker.markDown(2);
        tracker.markDown(0x20000032); // unicode code point above the BMP

        assertFalse("releasing one id must not disturb the others", tracker.markUp(1));
        assertArrayEquals(new int[] {0, 2, 0x20000032}, tracker.drain());
    }

    @Test
    public void drain_returnsHeldIdsInInsertionOrderAndClears() {
        InputDownTracker tracker = new InputDownTracker();

        tracker.markDown(3);
        tracker.markDown(1);
        tracker.markDown(2);

        assertArrayEquals(new int[] {3, 1, 2}, tracker.drain());
        assertArrayEquals("the first drain clears the set", new int[0], tracker.drain());
    }

    @Test
    public void drain_onFreshTracker_isEmpty() {
        assertArrayEquals(new int[0], new InputDownTracker().drain());
    }

    @Test
    public void drain_reflectsDownsAndUpsSinceLastDrain() {
        InputDownTracker tracker = new InputDownTracker();

        tracker.markDown(10);
        tracker.markDown(11);
        tracker.markDown(12);
        tracker.markUp(11); // 11 released before the first drain

        assertArrayEquals("the released id is absent from the drain", new int[] {10, 12}, tracker.drain());

        tracker.markDown(13); // only events after the drain are held now
        assertArrayEquals(new int[] {13}, tracker.drain());
    }
}

/*
 * Logic Verified: normal DOWN/UP cycle, orphan UP, auto-repeat DOWN idempotence (single
 * held entry, single UP releases it), re-press after release, independent tracking of
 * distinct ids (including 0 and 0x20000032), drain insertion order + clearing, empty
 * drain on a fresh tracker, and drain reflecting the held set after removed ups.
 * Bugs Documented: None.
 * Fakes Updated: None.
 * Review Status: Self-reviewed; independent review was not requested.
 */
