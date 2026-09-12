package com.undatech.opaque.input;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for {@link PointerAccelerationCurve}.
 * <p>
 * The class is pure Java: event times are supplied by the caller, so speed (dp/s),
 * dt clamping, EMA smoothing and the gain curve are all deterministic. Float
 * assertions use deltas; only the public gain value is observed.
 */
public class PointerAccelerationCurveTest {

    private static final float LOW = 0.5f;
    private static final float HIGH = 4f;

    private static PointerAccelerationCurve enabledCurve(float gainLow, float gainHigh) {
        PointerAccelerationCurve curve = new PointerAccelerationCurve();
        curve.setConfig(true, gainLow, gainHigh);
        return curve;
    }

    private static void assertFiniteGainInRange(float gain) {
        assertFalse("gain must not be NaN", Float.isNaN(gain));
        assertTrue("gain below floor: " + gain, gain >= 0.05f);
        assertTrue("gain above ceiling: " + gain, gain <= 20f);
    }

    @Test
    public void disabled_returnsFlatGainOfOne() {
        PointerAccelerationCurve curve = new PointerAccelerationCurve(); // constructor leaves it disabled

        assertEquals(1.0f, curve.gain(1000f, 0f, 0L), 1e-6f);
        assertEquals(1.0f, curve.gain(-5000f, 5000f, 999L), 1e-6f);

        curve.setConfig(false, 0.1f, 10f);
        assertEquals(1.0f, curve.gain(10000f, 0f, 1000L), 1e-6f);
    }

    @Test
    public void enabledWithZeroOrNonFiniteMovement_returnsGainLow() {
        PointerAccelerationCurve curve = enabledCurve(LOW, HIGH);

        assertEquals(LOW, curve.gain(0f, 0f, 16L), 1e-4f);
        assertEquals(LOW, curve.gain(Float.NaN, Float.POSITIVE_INFINITY, 32L), 1e-4f);
    }

    @Test
    public void enabledWithSlowMovement_returnsGainNearGainLow() {
        PointerAccelerationCurve curve = enabledCurve(LOW, HIGH);

        float gain = curve.gain(1f, 0f, 16L); // 62.5 dp/s

        assertTrue(gain >= LOW);
        assertEquals(0.5087f, gain, 0.01f); // gainLow + 3.5 * 25^2 / (25^2 + 500^2)
    }

    @Test
    public void gainIncreasesMonotonicallyWithSpeed() {
        float slow = enabledCurve(LOW, HIGH).gain(1f, 0f, 16L);    // 62.5 dp/s
        float medium = enabledCurve(LOW, HIGH).gain(8f, 0f, 16L);  // 500 dp/s (curve knee)
        float fast = enabledCurve(LOW, HIGH).gain(100f, 0f, 16L);  // 6250 dp/s

        assertTrue(slow < medium);
        assertTrue(medium < fast);
        assertTrue(fast <= HIGH);
    }

    @Test
    public void singleSampleAtReferenceSpeed_doesNotReachMidpointUntilEmaRamps() {
        PointerAccelerationCurve curve = enabledCurve(0f, 1f);

        float first = curve.gain(8f, 0f, 16L); // exactly 500 dp/s == V_REF
        assertTrue("EMA must hold the first sample below the midpoint", first > 0.1f && first < 0.25f);

        long time = 16L;
        float last = first;
        for (int i = 0; i < 20; i++) { // vS = 0.4 * 500 + 0.6 * vS converges to 500
            time += 16L;
            last = curve.gain(8f, 0f, time);
        }

        assertTrue(last > first);
        assertEquals(0.5f, last, 0.02f);
    }

    @Test
    public void directionReversal_onEitherAxis_resetsSmoothedVelocity() {
        PointerAccelerationCurve xAxis = enabledCurve(LOW, HIGH);
        long time = 16L;
        float xBefore = 0f;
        for (int i = 0; i < 10; i++) {
            xBefore = xAxis.gain(16f, 0f, time);
            time += 16L;
        }
        float xReversed = xAxis.gain(-16f, 0f, time);
        assertTrue("X reversal must drop the smoothed speed", xReversed < xBefore - 1.0f);

        PointerAccelerationCurve yAxis = enabledCurve(LOW, HIGH);
        time = 16L;
        float yBefore = 0f;
        for (int i = 0; i < 10; i++) {
            yBefore = yAxis.gain(0f, 16f, time);
            time += 16L;
        }
        float yReversed = yAxis.gain(0f, -16f, time);
        assertTrue("Y reversal must drop the smoothed speed", yReversed < yBefore - 1.0f);
    }

    @Test
    public void firstSampleUsesDefaultStep_laterElapsedTimesAreClamped() {
        PointerAccelerationCurve curve = enabledCurve(0f, 1f);

        float first = curve.gain(8f, 0f, 1_000_000L); // huge event time, but first sample assumes 16 ms
        assertEquals(0.1379f, first, 0.01f);

        float hugeGap = curve.gain(8f, 0f, 1_010_000L);     // 10 s gap -> clamped to 100 ms
        float zeroGap = curve.gain(8f, 0f, 1_010_000L);     // 0 ms gap -> clamped to 5 ms
        float backwardGap = curve.gain(8f, 0f, 1_005_000L); // negative gap -> clamped to 5 ms

        assertFiniteGainInRange(hugeGap);
        assertFiniteGainInRange(zeroGap);
        assertFiniteGainInRange(backwardGap);
    }

    @Test
    public void setConfig_withGainHighBelowGainLow_raisesGainHighToGainLow() {
        PointerAccelerationCurve curve = enabledCurve(5f, 2f);

        assertEquals(5f, curve.gain(0f, 0f, 16L), 1e-4f);
        assertEquals(5f, curve.gain(1000f, 0f, 32L), 1e-4f);
    }

    @Test
    public void setConfig_withInvalidGains_sanitizesToSafeValues() {
        PointerAccelerationCurve negativeLow = enabledCurve(-3f, 2f);
        assertEquals(0.05f, negativeLow.gain(0f, 0f, 16L), 1e-4f); // gainLow clamped to 0, output floor applies
        float fast = negativeLow.gain(1000f, 0f, 32L);
        assertTrue(fast > 1.5f && fast <= 2f);

        PointerAccelerationCurve nonFinite = enabledCurve(Float.NaN, Float.POSITIVE_INFINITY);
        assertEquals(0.05f, nonFinite.gain(1000f, 0f, 16L), 1e-4f);

        PointerAccelerationCurve nanHigh = enabledCurve(1f, Float.NaN);
        assertEquals(1f, nanHigh.gain(1000f, 0f, 16L), 1e-4f); // gainHigh falls back to gainLow
    }

    @Test
    public void gain_isClampedToRange() {
        PointerAccelerationCurve maxCurve = enabledCurve(0f, 100f);
        assertEquals(20f, maxCurve.gain(10000f, 0f, 16L), 1e-3f); // raw result ~100, clamped to ceiling

        PointerAccelerationCurve zeroCurve = enabledCurve(0f, 0f);
        assertEquals(0.05f, zeroCurve.gain(10000f, 0f, 16L), 1e-4f); // raw result 0, clamped to floor
    }

    @Test
    public void reset_clearsSmoothedVelocity() {
        PointerAccelerationCurve curve = enabledCurve(0f, 1f);
        long time = 16L;
        float before = 0f;
        for (int i = 0; i < 10; i++) {
            before = curve.gain(16f, 0f, time); // 1000 dp/s sustained
            time += 16L;
        }
        assertTrue(before > 0.7f);

        curve.reset();
        float after = curve.gain(16f, 0f, time); // first sample after reset restarts the EMA

        assertTrue(after < before);
        assertEquals(0.39f, after, 0.03f); // vS = 0.4 * 1000, not the pre-reset ~994
    }
}

/*
 * Logic Verified: flat gain while disabled (including the default-off constructor),
 * gainLow at zero/non-finite movement, monotonic speed-to-gain ramp, EMA smoothing,
 * direction-reversal reset on both axes, first-sample 16 ms default and elapsed-time
 * clamping (huge/zero/negative), gain sanitization (gainHigh < gainLow, negative and
 * non-finite gains), output clamping to [0.05, 20], and reset clearing smoothed velocity.
 * Bugs Documented: None.
 * Fakes Updated: None.
 * Review Status: Self-reviewed; independent review was not requested.
 */
