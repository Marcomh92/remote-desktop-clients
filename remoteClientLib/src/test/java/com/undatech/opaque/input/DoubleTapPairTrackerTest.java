package com.undatech.opaque.input;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for {@link DoubleTapPairTracker}.
 * <p>
 * Positions are in pixels and all times are caller-supplied milliseconds, so every
 * scenario is fully deterministic (the tracker never reads a clock). Assertions
 * cover only the public contract: the boolean returned by the second DOWN and the
 * observable effect of UP/reset on later DOWN events.
 */
public class DoubleTapPairTrackerTest {

    private static final int SLOP = 10;
    private static final long TIMEOUT = 300L;
    private static final int TAP_REGION = 10;

    private static DoubleTapPairTracker newTracker() {
        return new DoubleTapPairTracker(SLOP, TIMEOUT, TAP_REGION);
    }

    /** Records one complete tap: DOWN at (x, y), tap-like UP at the same point. */
    private static void recordTap(DoubleTapPairTracker tracker, float x, float y, long downTimeMs, long upTimeMs) {
        assertFalse("first DOWN of a tap must not complete a pair", tracker.onActionDown(x, y, downTimeMs));
        tracker.onActionUp(x, y, upTimeMs);
    }

    @Test
    public void onActionDown_secondTapWithinSlopAndTimeout_returnsTrue() {
        DoubleTapPairTracker tracker = newTracker();

        assertFalse(tracker.onActionDown(100f, 100f, 1_000L));
        tracker.onActionUp(105f, 100f, 1_100L); // 5 px from DOWN: tap-like

        assertTrue(tracker.onActionDown(110f, 100f, 1_200L)); // 10 px apart, 100 ms after UP
    }

    @Test
    public void onActionDown_atExactSlopBoundary_matches_butJustBeyondDoesNot() {
        DoubleTapPairTracker atBoundary = newTracker();
        recordTap(atBoundary, 0f, 0f, 0L, 10L);
        assertTrue(atBoundary.onActionDown(10f, 0f, 20L)); // distance == slop

        DoubleTapPairTracker beyond = newTracker();
        recordTap(beyond, 0f, 0f, 0L, 10L);
        assertFalse(beyond.onActionDown(11f, 0f, 20L)); // distance > slop
    }

    @Test
    public void onActionDown_atExactTimeoutBoundary_matches_butJustBeyondDoesNot() {
        DoubleTapPairTracker atBoundary = newTracker();
        recordTap(atBoundary, 0f, 0f, 0L, 50L);
        assertTrue(atBoundary.onActionDown(0f, 0f, 350L)); // exactly timeoutMs after UP

        DoubleTapPairTracker beyond = newTracker();
        recordTap(beyond, 0f, 0f, 0L, 50L);
        assertFalse(beyond.onActionDown(0f, 0f, 351L)); // 301 ms after UP
    }

    @Test
    public void onActionDown_justBeyondTimeout_startsNewPendingTap() {
        DoubleTapPairTracker tracker = new DoubleTapPairTracker(SLOP, 100L, TAP_REGION);
        recordTap(tracker, 0f, 0f, 0L, 10L);

        assertFalse(tracker.onActionDown(0f, 0f, 111L)); // 101 ms after UP: too late, becomes the new pending tap
        tracker.onActionUp(0f, 0f, 120L);
        assertTrue(tracker.onActionDown(0f, 0f, 130L)); // pairs with the replacement DOWN
    }

    @Test
    public void onActionDown_usesEuclideanDistanceAcrossBothAxes() {
        DoubleTapPairTracker exact = new DoubleTapPairTracker(5, TIMEOUT, TAP_REGION);
        recordTap(exact, 0f, 0f, 0L, 10L);
        assertTrue(exact.onActionDown(3f, 4f, 20L)); // hypot(3, 4) == 5 == slop

        DoubleTapPairTracker beyond = new DoubleTapPairTracker(5, TIMEOUT, TAP_REGION);
        recordTap(beyond, 0f, 0f, 0L, 10L);
        assertFalse(beyond.onActionDown(4f, 4f, 20L)); // hypot(4, 4) > 5
    }

    @Test
    public void onActionUp_atTapRegionBoundary_isRecorded_butBeyondClearsPendingTap() {
        DoubleTapPairTracker atBoundary = new DoubleTapPairTracker(SLOP, TIMEOUT, 5);
        assertFalse(atBoundary.onActionDown(0f, 0f, 0L));
        atBoundary.onActionUp(3f, 4f, 50L); // hypot(3, 4) == 5 == tapRegion
        assertTrue(atBoundary.onActionDown(0f, 0f, 100L));

        DoubleTapPairTracker beyond = new DoubleTapPairTracker(SLOP, TIMEOUT, 5);
        assertFalse(beyond.onActionDown(0f, 0f, 0L));
        beyond.onActionUp(4f, 4f, 50L); // hypot(4, 4) > 5: a drag, pending tap discarded
        assertFalse(beyond.onActionDown(0f, 0f, 100L));
        beyond.onActionUp(0f, 0f, 110L); // a fresh tap can still be recorded afterwards
        assertTrue(beyond.onActionDown(0f, 0f, 120L));
    }

    @Test
    public void onActionUp_withoutPendingDown_isIgnored() {
        DoubleTapPairTracker tracker = newTracker();

        tracker.onActionUp(50f, 50f, 100L); // no DOWN seen: no-op

        assertFalse(tracker.onActionDown(50f, 50f, 200L)); // stray UP must not have stored a tap
        tracker.onActionUp(50f, 50f, 210L);
        assertTrue(tracker.onActionDown(50f, 50f, 220L));
    }

    @Test
    public void onActionDown_withoutPreviousUp_replacesPendingTap() {
        DoubleTapPairTracker tracker = newTracker();

        assertFalse(tracker.onActionDown(0f, 0f, 0L));
        assertFalse(tracker.onActionDown(500f, 500f, 10L)); // first tap never received its UP

        tracker.onActionUp(500f, 500f, 20L);
        assertTrue(tracker.onActionDown(500f, 500f, 30L)); // pairs with the replacement DOWN
    }

    @Test
    public void onActionDown_afterCompletedPair_thirdDownStartsFreshTap() {
        DoubleTapPairTracker tracker = newTracker();
        recordTap(tracker, 0f, 0f, 0L, 10L);
        assertTrue(tracker.onActionDown(0f, 0f, 20L)); // pair completed and consumed

        assertFalse(tracker.onActionDown(0f, 0f, 30L)); // completing DOWN is not kept as a pending tap
        tracker.onActionUp(0f, 0f, 40L);
        assertTrue(tracker.onActionDown(0f, 0f, 50L));
    }

    @Test
    public void detectsRepeatedPairsInSequence() {
        DoubleTapPairTracker tracker = newTracker();

        recordTap(tracker, 10f, 10f, 0L, 10L);
        assertTrue(tracker.onActionDown(12f, 12f, 20L));

        recordTap(tracker, 200f, 200f, 100L, 110L);
        assertTrue(tracker.onActionDown(202f, 202f, 120L));

        tracker.onActionUp(0f, 0f, 130L); // stale UP with no pending DOWN is ignored
        recordTap(tracker, 500f, 500f, 140L, 150L);
        assertTrue(tracker.onActionDown(500f, 500f, 160L));
    }

    @Test
    public void reset_clearsStoredTap() {
        DoubleTapPairTracker tracker = newTracker();
        recordTap(tracker, 0f, 0f, 0L, 10L);

        tracker.reset();

        assertFalse(tracker.onActionDown(0f, 0f, 20L));
        tracker.onActionUp(0f, 0f, 30L);
        assertTrue(tracker.onActionDown(0f, 0f, 40L));
    }

    @Test
    public void setSlopPx_resetsStateAndAppliesNewSlop() {
        DoubleTapPairTracker tracker = new DoubleTapPairTracker(5, TIMEOUT, TAP_REGION);
        recordTap(tracker, 0f, 0f, 0L, 10L);

        tracker.setSlopPx(50);

        assertFalse(tracker.onActionDown(40f, 0f, 20L)); // stored tap was cleared
        tracker.onActionUp(40f, 0f, 30L);
        assertTrue(tracker.onActionDown(80f, 0f, 40L)); // 40 px apart: matches only with the new slop
    }

    @Test
    public void setTimeoutMs_resetsStateAndAppliesNewTimeout() {
        DoubleTapPairTracker tracker = new DoubleTapPairTracker(SLOP, 50L, TAP_REGION);
        recordTap(tracker, 0f, 0f, 0L, 10L);

        tracker.setTimeoutMs(1_000L);

        assertFalse(tracker.onActionDown(0f, 0f, 20L)); // stored tap was cleared
        tracker.onActionUp(0f, 0f, 30L);
        assertTrue(tracker.onActionDown(0f, 0f, 520L)); // 490 ms > old timeout, within the new one
    }

    @Test
    public void negativeThresholds_areClampedToZero() {
        DoubleTapPairTracker zeroSlop = new DoubleTapPairTracker(-1, -5L, -1);
        recordTap(zeroSlop, 7f, 7f, 0L, 0L);
        assertTrue(zeroSlop.onActionDown(7f, 7f, 0L)); // 0 px, 0 ms: within the clamped bounds

        DoubleTapPairTracker onePixelOff = new DoubleTapPairTracker(-1, -5L, -1);
        recordTap(onePixelOff, 7f, 7f, 0L, 0L);
        assertFalse(onePixelOff.onActionDown(8f, 7f, 0L)); // any movement exceeds slop 0
    }
}

/*
 * Logic Verified: inclusive slop/timeout/tap-region boundaries, Euclidean slop distance,
 * pair consumption (third DOWN starts fresh), tap-like UP recording vs. drag clearing,
 * DOWN-without-UP replacement, UP-without-DOWN no-op, reset and setter-triggered reset,
 * repeated pairs, and clamping of negative thresholds to zero.
 * Bugs Documented: None.
 * Fakes Updated: None.
 * Review Status: Self-reviewed; independent review was not requested.
 */
